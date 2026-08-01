package com.javaee.aiservice.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String DOCUMENT_PREFIX = "doc:";
    private static final String CONTENT_PREFIX = "content:";
    private static final String SEGMENT_PREFIX = "segment:";
    private static final String DOC_SEGMENTS_PREFIX = "doc_segments:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DocumentVectorizer vectorizer;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Reranker reranker;

    @Autowired
    private DocumentSegmenter documentSegmenter;

    @Autowired
    private TextTokenizer textTokenizer;

    @Value("${rag.bm25.enabled:true}")
    private boolean bm25Enabled;

    // 双路召回深度（向量 + BM25 各召回这么多候选做 RRF 融合）。默认 50 覆盖中小语料全量，
    // 保证向量漏召（排在 recallDepth 之外）的相关文档也能被 BM25 经融合推回 top-K。
    // 语料增大时可下调以控延迟；增大到超过语料量无额外收益。
    @Value("${rag.recall.depth:50}")
    private int recallDepth;

    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        addDocumentWithSegment(documentId, content, metadata, DocumentSegmenter.StrategyType.AUTO);
    }

    public void addDocumentWithSegment(String documentId, String content, Map<String, Object> metadata,
                                       DocumentSegmenter.StrategyType strategyType) {
        log.info("添加文档到知识库: documentId={}, strategy={}", documentId, strategyType);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;
            String contentKey = CONTENT_PREFIX + documentId;

            Map<String, Object> docMetadata = normalizeMetadata(metadata);
            docMetadata.put("strategy", strategyType.name());
            docMetadata.put("totalLength", content.length());

            redisTemplate.opsForValue().set(contentKey, content);
            redisTemplate.opsForHash().putAll(docKey, docMetadata);

            List<SegmentStrategy.Segment> segments = documentSegmenter.segment(documentId, content, strategyType);

            if (segments.isEmpty()) {
                log.warn("文档分段结果为空，直接存储完整文档");
                float[] vector = vectorizer.vectorize(content);
                vectorStore.store(documentId, vector, docMetadata);
                return;
            }

            List<String> segmentIds = new ArrayList<>();
            for (SegmentStrategy.Segment segment : segments) {
                String segmentId = segment.getSegmentId();
                segmentIds.add(segmentId);

                String segmentContentKey = SEGMENT_PREFIX + segmentId;
                redisTemplate.opsForValue().set(segmentContentKey, segment.getContent());

                Map<String, Object> segmentMetadata = new HashMap<>(docMetadata);
                segmentMetadata.put("documentId", documentId);
                segmentMetadata.put("segmentIndex", segment.getIndex());
                segmentMetadata.put("segmentTitle", segment.getTitle());
                segmentMetadata.put("charCount", segment.getCharCount());

                float[] vector = vectorizer.vectorize(segment.getContent());
                vectorStore.store(segmentId, vector, segmentMetadata);
            }

            redisTemplate.opsForValue().set(DOC_SEGMENTS_PREFIX + documentId, segmentIds);

            log.info("文档添加成功: documentId={}, 分段数={}", documentId, segments.size());
        } catch (Exception e) {
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    public void addDocument(String documentId, String content, Map<String, Object> metadata,
                           DocumentSegmenter.StrategyType strategyType) {
        addDocumentWithSegment(documentId, content, metadata, strategyType);
    }

    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);

            for (String segmentId : segmentIds) {
                redisTemplate.delete(SEGMENT_PREFIX + segmentId);
                vectorStore.delete(segmentId);
            }

            redisTemplate.delete(DOC_SEGMENTS_PREFIX + documentId);
            redisTemplate.delete(DOCUMENT_PREFIX + documentId);
            redisTemplate.delete(CONTENT_PREFIX + documentId);

            log.info("文档移除成功: documentId={}, 删除了{}个分段", documentId, segmentIds.size());
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    public String getDocumentContent(String documentId) {
        try {
            return (String) redisTemplate.opsForValue().get(CONTENT_PREFIX + documentId);
        } catch (Exception e) {
            log.warn("获取文档内容失败", e);
            return null;
        }
    }

    public String getSegmentContent(String segmentId) {
        try {
            return (String) redisTemplate.opsForValue().get(SEGMENT_PREFIX + segmentId);
        } catch (Exception e) {
            log.warn("获取分段内容失败", e);
            return null;
        }
    }

    public List<String> getSegmentIds(String documentId) {
        try {
            Object segmentIdsObj = redisTemplate.opsForValue().get(DOC_SEGMENTS_PREFIX + documentId);
            if (segmentIdsObj == null) {
                return Collections.emptyList();
            }
            if (segmentIdsObj instanceof List) {
                return ((List<?>) segmentIdsObj).stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("获取文档分段ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getDocumentMetadata(String documentId) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(DOCUMENT_PREFIX + documentId);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("获取文档元数据失败", e);
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> getDocumentSegments(String documentId) {
        log.info("获取文档分段: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);
            List<Map<String, Object>> segments = new ArrayList<>();

            for (String segmentId : segmentIds) {
                Map<String, Object> segmentInfo = new HashMap<>();
                segmentInfo.put("segmentId", segmentId);
                segmentInfo.put("content", getSegmentContent(segmentId));
                segments.add(segmentInfo);
            }

            return segments;
        } catch (Exception e) {
            log.error("获取文档分段失败", e);
            throw new RuntimeException("获取文档分段失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType) {
        return search(query, topK, strategyType, Collections.emptyMap());
    }

    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType,
                                            Map<String, Object> filters) {
        log.info("搜索知识库: query={}, topK={}, strategy={}", query, topK, strategyType);

        try {
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> results = vectorStore.search(queryVector, topK, filters);

            for (Map<String, Object> result : results) {
                String id = (String) result.get("id");
                String content = getSegmentContent(id);
                if (content == null) {
                    content = getDocumentContent(id);
                }
                result.put("content", content);
            }

            return results;
        } catch (Exception e) {
            log.error("知识库搜索失败", e);
            throw new RuntimeException("知识库搜索失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        return hybridSearch(query, topK, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK,
                                                   DocumentSegmenter.StrategyType strategyType) {
        return hybridSearch(query, topK, strategyType, Collections.emptyMap());
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK,
                                                   DocumentSegmenter.StrategyType strategyType,
                                                   Map<String, Object> filters) {
        log.info("混合检索: query={}, topK={}, strategy={}", query, topK, strategyType);

        try {
            // 两路召回深度必须相等：RRF 融合分 = 1/(60+rank)，若一路召回浅、一路召回深，
            // 浅的那路在融合里天然吃亏、召回扩展被压制。recallDepth 来自配置 rag.recall.depth（默认 50），
            // 覆盖全量候选，BM25 才能把向量漏召（排在 recallDepth 之外）的文档经融合推回 top-K。
            float[] queryVector = vectorizer.vectorize(query);
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, recallDepth, filters);

            List<Map<String, Object>> bm25Results = bm25Search(query, recallDepth, filters);

            // RRF(Reciprocal Rank Fusion) 融合：两路各自按自身分数降序排名，
            // 每篇文档融合分 = Σ 1/(k + rank)，k=60。这样在某一路排名高、另一路未召回的
            // 文档也能浮到前列，BM25 真正参与混合检索（而非被追加到末尾后被截断丢弃）。
            // 不依赖向量余弦与 BM25 分数的量纲对齐，鲁棒。
            Map<String, Double> rrfScores = new HashMap<>();
            Map<String, Map<String, Object>> resultById = new LinkedHashMap<>();
            final int rrfK = 60;

            for (int i = 0; i < vectorResults.size(); i++) {
                Map<String, Object> r = vectorResults.get(i);
                String id = (String) r.get("id");
                rrfScores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
                r.put("source", "vector");
                if (!resultById.containsKey(id)) {
                    String content = getSegmentContent(id);
                    if (content == null) {
                        content = getDocumentContent(id);
                    }
                    r.put("content", content);
                    resultById.put(id, r);
                }
            }

            for (int i = 0; i < bm25Results.size(); i++) {
                Map<String, Object> r = bm25Results.get(i);
                String id = (String) r.get("id");
                rrfScores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
                r.put("source", "bm25");
                if (!resultById.containsKey(id)) {
                    String content = getSegmentContent(id);
                    if (content == null) {
                        content = getDocumentContent(id);
                    }
                    r.put("content", content);
                    resultById.put(id, r);
                }
            }

            List<Map<String, Object>> combinedResults = new ArrayList<>(resultById.values());
            combinedResults.sort((a, b) -> Double.compare(
                    rrfScores.getOrDefault(b.get("id"), 0.0),
                    rrfScores.getOrDefault(a.get("id"), 0.0)
            ));

            return combinedResults.subList(0, Math.min(topK, combinedResults.size()));

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType) {
        return hybridSearchWithRerank(query, topK, rerankStrategy, strategyType, Collections.emptyMap());
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType,
                                                            Map<String, Object> filters) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}, rerankStrategy={}",
                query, topK, strategyType, rerankStrategy);

        try {
            List<Map<String, Object>> candidates = hybridSearch(query, recallDepth, strategyType, filters);

            List<Map<String, Object>> results = reranker.rerank(query, candidates, rerankStrategy, topK);

            return results;
        } catch (Exception e) {
            log.error("混合检索加重排序失败", e);
            throw new RuntimeException("混合检索加重排序失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy) {
        return hybridSearchWithRerank(query, topK, rerankStrategy, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            String userId,
                                                            String knowledgeBaseId) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", userId);
        filters.put("knowledgeBaseId", knowledgeBaseId);
        return hybridSearchWithRerank(query, topK, rerankStrategy, DocumentSegmenter.StrategyType.CHAPTER, filters);
    }

    private List<Map<String, Object>> bm25Search(String query, int topK, Map<String, Object> filters) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (!bm25Enabled) {
            return results;
        }

        // 只扫描 segment（chunk）级 key —— 与向量路返回的 chunk 级 id 对齐，
        // 让 BM25 与向量在同一 id 空间做 RRF 融合；不再混入 document 级 id 造成评测 null。
        List<String> segmentKeys = scanKeys(SEGMENT_PREFIX + "*", 1000);

        Set<String> allKeys = new LinkedHashSet<>();
        if (segmentKeys != null) allKeys.addAll(segmentKeys);

        if (allKeys.isEmpty()) {
            return results;
        }

        // 1) 收集候选 chunk 并统一分词（与 Reranker 共用 TextTokenizer，BM25 查询期现切，无需 reindex）
        List<Bm25Doc> docs = new ArrayList<>();
        for (String key : allKeys) {
            String chunkId = key.substring(key.lastIndexOf(":") + 1);
            Map<String, Object> metadata = vectorStoreMetadata(chunkId);
            if (!matchesFilters(metadata, filters)) {
                continue;
            }
            String content = (String) redisTemplate.opsForValue().get(key);
            if (content == null) {
                continue;
            }
            List<String> tokens = textTokenizer.tokenize(content);
            if (!tokens.isEmpty()) {
                // 从分段元数据取所属文档 id（chunk 级 id 有多后缀，不靠截取，直接读 metadata 最稳）
                Object docIdObj = metadata.get("documentId");
                String documentId = docIdObj != null ? docIdObj.toString() : chunkId;
                docs.add(new Bm25Doc(chunkId, documentId, tokens));
            }
        }

        if (docs.isEmpty()) {
            return results;
        }

        // 2) 统计文档频率 DF 与平均文档长度（在扫描到的语料内计算真实 IDF）
        Map<String, Integer> df = new HashMap<>();
        int totalLength = 0;
        for (Bm25Doc doc : docs) {
            totalLength += doc.tokens.size();
            for (String term : new HashSet<>(doc.tokens)) {
                df.merge(term, 1, Integer::sum);
            }
        }
        int N = docs.size();
        float avgdl = (float) totalLength / N;

        // 3) 对 query 分词并做标准 BM25 打分（词频 TF + 逆文档频率 IDF）
        List<String> queryTokens = textTokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return results;
        }

        float k = 2.2f;
        float b = 0.75f;
        for (Bm25Doc doc : docs) {
            float score = 0.0f;
            Map<String, Integer> tfMap = termFreq(doc.tokens);
            int docLen = doc.tokens.size();
            for (String qt : queryTokens) {
                Integer tf = tfMap.get(qt);
                if (tf == null || tf == 0) {
                    continue;
                }
                int n = df.getOrDefault(qt, 0);
                float idf = (float) Math.log((N - n + 0.5) / (n + 0.5) + 1.0);
                float tfNorm = (tf * (k + 1)) / (tf + k * (1 - b + b * docLen / avgdl));
                score += idf * tfNorm;
            }
            if (score > 0) {
                Map<String, Object> hit = new HashMap<>();
                hit.put("id", doc.chunkId);            // chunk 级 id，与向量对齐
                hit.put("documentId", doc.documentId); // 所属文档 id，评测取此字段
                hit.put("similarity", score);
                results.add(hit);
            }
        }

        results.sort((o1, o2) -> Float.compare(
            ((Number) o2.get("similarity")).floatValue(),
            ((Number) o1.get("similarity")).floatValue()
        ));

        return results.subList(0, Math.min(topK, results.size()));
    }

    private static final class Bm25Doc {
        final String chunkId;
        final String documentId;
        final List<String> tokens;

        Bm25Doc(String chunkId, String documentId, List<String> tokens) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.tokens = tokens;
        }
    }

    private static Map<String, Integer> termFreq(List<String> tokens) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        return tf;
    }

    public List<String> getAllDocumentIds() {
        try {
            List<String> keys = scanKeys(DOCUMENT_PREFIX + "*", 1000);
            if (keys == null) {
                return Collections.emptyList();
            }
            return keys.stream()
                .map(key -> key.substring(DOCUMENT_PREFIX.length()))
                .toList();
        } catch (Exception e) {
            log.warn("获取文档ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public List<String> getAllDocumentIds(String userId, String knowledgeBaseId) {
        return getAllDocumentIds().stream()
                .filter(documentId -> matchesFilters(getDocumentMetadata(documentId), Map.of(
                        "userId", userId,
                        "knowledgeBaseId", knowledgeBaseId
                )))
                .toList();
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("更新文档: documentId={}", documentId);
        removeDocument(documentId);
        addDocumentWithSegment(documentId, content, metadata, DocumentSegmenter.StrategyType.AUTO);
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata,
                              DocumentSegmenter.StrategyType strategyType) {
        log.info("更新文档: documentId={}, strategy={}", documentId, strategyType);
        removeDocument(documentId);
        addDocumentWithSegment(documentId, content, metadata, strategyType);
    }

    public Map<String, Object> getStatistics() {
        return getStatistics(null, null);
    }

    public Map<String, Object> getStatistics(String userId, String knowledgeBaseId) {
        Map<String, Object> stats = new HashMap<>();

        List<String> docIds = (userId == null || knowledgeBaseId == null)
                ? getAllDocumentIds()
                : getAllDocumentIds(userId, knowledgeBaseId);
        stats.put("documentCount", docIds.size());

        int totalSegments = 0;
        for (String docId : docIds) {
            totalSegments += getSegmentIds(docId).size();
        }
        stats.put("segmentCount", totalSegments);

        long totalContentSize = 0;
        for (String docId : docIds) {
            String content = getDocumentContent(docId);
            if (content != null) {
                totalContentSize += content.length();
            }
        }
        stats.put("totalContentSize", totalContentSize);

        return stats;
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        normalized.putIfAbsent("userId", "system");
        normalized.putIfAbsent("knowledgeBaseId", "default");
        return normalized;
    }

    private List<String> scanKeys(String pattern, int count) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object expected = filter.getValue();
            if (expected == null || expected.toString().isBlank()) {
                continue;
            }
            Object actual = metadata.get(filter.getKey());
            if (actual == null || !expected.toString().equals(actual.toString())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> vectorStoreMetadata(String id) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries("metadata:" + id);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
