package com.javaee.aiservice.rag;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 向量存储（Qdrant 实现）。
 * <p>
 * 由 {@code rag.vector.backend=qdrant} 激活。相比默认 HNSW 实现：
 * <ul>
 *   <li>原生持久化，重启即加载，无需从 Redis 重建内存索引</li>
 *   <li>多租户过滤（userId / knowledgeBaseId）下推到库内执行，根治"召回被筛空"隐患</li>
 *   <li>所有副本共享同一向量库，不再各自内存一份</li>
 * </ul>
 *
 * 注意：Qdrant 的 point id 仅支持 UUID / 无符号整数，不支持任意字符串。
 * 因此把业务字符串 id（如 {@code doc-x_seg_0}）用 nameUUIDFromBytes 哈希为稳定 UUID 作为库内主键，
 * 原始字符串 id 存入 payload 的 {@code id} 字段，检索结果再回带，对外契约不变。
 */
@Component
@ConditionalOnProperty(name = "rag.vector.backend", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int port;

    @Value("${qdrant.collection:docai}")
    private String collectionName;

    @Value("${ai.vector.dimension:1536}")
    private int dimension;

    private QdrantClient client;

    @PostConstruct
    public void init() {
        try {
            QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, false);
            this.client = new QdrantClient(builder.build());
            log.info("Qdrant 客户端已连接: {}:{}", host, port);
        } catch (Exception e) {
            log.error("Qdrant 客户端初始化失败", e);
            throw new RuntimeException("Qdrant 客户端初始化失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 关闭失败不影响退出
            }
        }
    }

    @Override
    public void store(String id, float[] vector, String content, Map<String, Object> metadata) {
        log.info("Qdrant 存储向量: id={}, dimension={}", id, vector.length);
        try {
            ensureCollection();
            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            // 保留原始字符串 id，供检索结果回带（库内主键是哈希后的 UUID）
            payload.put("id", value(id));
            // 原文随向量进 payload，BM25 / 检索结果回带 content 均可直接从库内取，不再依赖 Redis segment:
            if (content != null) {
                payload.put("content", value(content));
            }
            if (metadata != null) {
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    payload.put(entry.getKey(), toValue(entry.getValue()));
                }
            }
            PointStruct point = PointStruct.newBuilder()
                    .setId(toPointId(id))
                    .setVectors(vectors(vector))
                    .putAllPayload(payload)
                    .build();
            client.upsertAsync(collectionName, List.of(point)).get();
            log.info("Qdrant 向量存储成功: id={}", id);
        } catch (Exception e) {
            log.error("Qdrant 向量存储失败: id={}", id, e);
            throw new RuntimeException("Qdrant 向量存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        return search(queryVector, topK, Collections.emptyMap());
    }

    @Override
    public List<Map<String, Object>> search(float[] queryVector, int topK, Map<String, Object> filters) {
        try {
            if (!collectionExists()) {
                return Collections.emptyList();
            }
            SearchPoints.Builder q = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
            Filter filter = buildFilter(filters);
            if (filter != null) {
                q.setFilter(filter);
            }
            List<ScoredPoint> hits = client.searchAsync(q.build()).get();
            List<Map<String, Object>> results = new ArrayList<>(hits.size());
            for (ScoredPoint hit : hits) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", extractId(hit));
                item.put("similarity", (float) hit.getScore());
                Map<String, JsonWithInt.Value> payload = hit.getPayloadMap();
                for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
                    if ("id".equals(entry.getKey())) {
                        continue; // 原始 id 已作为 item.id 处理
                    }
                    Object v = fromValue(entry.getValue());
                    if (v != null) {
                        item.put(entry.getKey(), v);
                    }
                }
                results.add(item);
            }
            log.info("Qdrant 搜索完成，找到{}个结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("Qdrant 向量搜索失败", e);
            throw new RuntimeException("Qdrant 向量搜索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        log.info("Qdrant 删除向量: id={}", id);
        try {
            if (!collectionExists()) {
                return;
            }
            client.deleteAsync(collectionName, List.of(toPointId(id))).get();
            log.info("Qdrant 向量删除成功: id={}", id);
        } catch (Exception e) {
            log.error("Qdrant 向量删除失败: id={}", id, e);
            throw new RuntimeException("Qdrant 向量删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getContent(String id) {
        if (!collectionExists()) {
            return null;
        }
        try {
            Filter filter = Filter.newBuilder()
                    .addMust(Condition.newBuilder()
                            .setField(Points.FieldCondition.newBuilder()
                                    .setKey("id")
                                    .setMatch(Match.newBuilder().setKeyword(id).build())
                                    .build())
                            .build())
                    .build();
            ScrollPoints req = ScrollPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setLimit(1)
                    .setFilter(filter)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            ScrollResponse resp = client.scrollAsync(req).get();
            for (RetrievedPoint p : resp.getResultList()) {
                JsonWithInt.Value c = p.getPayloadMap().get("content");
                if (c != null && c.hasStringValue()) {
                    return c.getStringValue();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Qdrant 获取 chunk 原文失败: id={}", id, e);
            return null;
        }
    }

    // ---- 内部辅助 ----

    private boolean collectionExists() {
        try {
            return client.collectionExistsAsync(collectionName).get();
        } catch (Exception e) {
            log.warn("检查 Qdrant collection 失败: {}", e.getMessage());
            return false;
        }
    }

    private void ensureCollection() {
        try {
            if (client.collectionExistsAsync(collectionName).get()) {
                return;
            }
            client.createCollectionAsync(collectionName,
                    VectorParams.newBuilder()
                            .setDistance(Distance.Cosine)
                            .setSize(dimension)
                            .build()).get();
            log.info("Qdrant collection 已创建: {}", collectionName);
        } catch (Exception e) {
            log.warn("确保 Qdrant collection 失败: {}", e.getMessage());
        }
    }

    /** 业务字符串 id → 库内 UUID 主键（确定性哈希，同名同 UUID）。 */
    private Points.PointId toPointId(String id) {
        UUID uuid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        return PointIdFactory.id(uuid);
    }

    private String extractId(ScoredPoint hit) {
        JsonWithInt.Value idVal = hit.getPayloadMap().get("id");
        if (idVal != null && idVal.hasStringValue()) {
            return idVal.getStringValue();
        }
        return hit.getId() != null ? hit.getId().toString() : "";
    }

    /** 把 Object 转成 Qdrant Value（覆盖 metadata 实际出现的类型：String/Boolean/Number）。 */
    private JsonWithInt.Value toValue(Object o) {
        if (o == null) {
            return value("");
        }
        if (o instanceof String s) {
            return value(s);
        }
        if (o instanceof Boolean b) {
            return value(b);
        }
        if (o instanceof Number n) {
            if (n instanceof Integer || n instanceof Long) {
                return value(n.longValue());
            }
            return value(n.doubleValue());
        }
        return value(String.valueOf(o));
    }

    /** 把 Qdrant Value 还原回 Java 对象。 */
    private Object fromValue(JsonWithInt.Value v) {
        switch (v.getKindCase()) {
            case STRING_VALUE:
                return v.getStringValue();
            case INTEGER_VALUE:
                return v.getIntegerValue();
            case DOUBLE_VALUE:
                return v.getDoubleValue();
            case BOOL_VALUE:
                return v.getBoolValue();
            default:
                return null;
        }
    }

    private Filter buildFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        Filter.Builder fb = Filter.newBuilder();
        for (Map.Entry<String, Object> f : filters.entrySet()) {
            Object expected = f.getValue();
            if (expected == null || expected.toString().isBlank()) {
                continue;
            }
            Condition cond = Condition.newBuilder()
                    .setField(Points.FieldCondition.newBuilder()
                            .setKey(f.getKey())
                            .setMatch(Match.newBuilder()
                                    .setKeyword(expected.toString())
                                    .build())
                            .build())
                    .build();
            fb.addMust(cond);
        }
        return fb.build();
    }

    @Override
    public Map<String, Map<String, Object>> scanChunks(Map<String, Object> filters) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (!collectionExists()) {
            return result;
        }
        Filter filter = buildFilter(filters);
        Points.PointId offset = null;
        final int pageSize = 256;
        try {
            while (true) {
                ScrollPoints.Builder b = ScrollPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .setLimit(pageSize)
                        // BM25 只需要 payload 原文与元数据，不需要回传 1536 维向量
                        .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build())
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
                if (filter != null) {
                    b.setFilter(filter);
                }
                if (offset != null) {
                    b.setOffset(offset);
                }
                ScrollResponse resp = client.scrollAsync(b.build()).get();
                for (RetrievedPoint p : resp.getResultList()) {
                    String id = extractId(p);
                    Map<String, Object> meta = payloadToMap(p.getPayloadMap());
                    if (!meta.isEmpty()) {
                        result.put(id, meta);
                    }
                }
                // 注意：protobuf 的 getNextPageOffset() 永远不会返回 null，
                // 末页时返回的是「未设置 point_id_options 的空 PointId」，
                // 若直接拿它当下一页 offset 回传，服务端会报 INVALID_ARGUMENT: No ID options provided。
                // 必须用 hasNextPageOffset() 判断是否真的还有下一页。
                if (!resp.hasNextPageOffset()) {
                    break;
                }
                offset = resp.getNextPageOffset();
            }
            return result;
        } catch (Exception e) {
            log.error("Qdrant 扫描元数据失败", e);
            return result;
        }
    }

    private String extractId(RetrievedPoint p) {
        JsonWithInt.Value idVal = p.getPayloadMap().get("id");
        if (idVal != null && idVal.hasStringValue()) {
            return idVal.getStringValue();
        }
        return p.getId() != null ? p.getId().toString() : "";
    }

    private Map<String, Object> payloadToMap(Map<String, JsonWithInt.Value> payload) {
        Map<String, Object> item = new HashMap<>();
        for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
            if ("id".equals(entry.getKey())) {
                continue;
            }
            Object v = fromValue(entry.getValue());
            if (v != null) {
                item.put(entry.getKey(), v);
            }
        }
        return item;
    }

    private List<Float> toFloatList(float[] v) {
        List<Float> list = new ArrayList<>(v.length);
        for (float x : v) {
            list.add(x);
        }
        return list;
    }
}
