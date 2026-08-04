package com.javaee.aiservice.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量存储抽象接口。
 * <p>
 * 两种实现可互换，业务层（KnowledgeBase / KnowledgeIndexAgent / RagController）只依赖本接口，
 * 通过 {@code rag.vector.backend} 配置切换，互不影响：
 * <ul>
 *   <li>{@code HnswRedisVectorStore}（默认）：HNSW 内存索引 + Redis 持久化</li>
 *   <li>{@code QdrantVectorStore}：Qdrant 向量库，过滤下推根治"召回被筛空"隐患</li>
 * </ul>
 *
 * 契约（实现类必须保证）：
 * <ul>
 *   <li>{@code store}：写入向量与元数据，元数据需随向量一起可回带（search 结果要包含）</li>
 *   <li>{@code search}：返回 List，每项含 {@code id}(原始字符串)、{@code similarity}(余弦相似度)、以及全部元数据键值</li>
 *   <li>{@code delete}：按原始字符串 id 删除</li>
 * </ul>
 */
public interface VectorStore {

    /**
     * 存储向量与原文。
     *
     * @param id       原始字符串 id（可能是 chunkId，如 doc-x_seg_0）
     * @param vector   向量（维度与 embedding 模型一致，默认 1536）
     * @param content  原文文本（随向量持久化并在检索时回带；Qdrant 存入 payload，Redis 存入 segment:）
     * @param metadata 元数据（随向量持久化并在检索时回带）
     */
    void store(String id, float[] vector, String content, Map<String, Object> metadata);

    /**
     * 检索相似向量（不过滤）。
     */
    List<Map<String, Object>> search(float[] queryVector, int topK);

    /**
     * 检索相似向量（带过滤条件，精确相等匹配）。
     */
    List<Map<String, Object>> search(float[] queryVector, int topK, Map<String, Object> filters);

    /**
     * 删除向量。
     */
    void delete(String id);

    /**
     * 按 id 取单 chunk 的原文（供检索结果回带 content / BM25 取 chunk 文本）。
     * <ul>
     *   <li>{@code HnswRedisVectorStore}：读 Redis {@code segment:}</li>
     *   <li>{@code QdrantVectorStore}：从 payload 取 {@code content}</li>
     * </ul>
     */
    String getContent(String id);

    /**
     * 按过滤条件扫描全库 chunk 的原文 + 元数据。
     * <p>
     * 供 BM25 路在检索期一次性拉取全部 chunk 文本与多租户元数据打分，与具体后端解耦：
     * <ul>
     *   <li>{@code HnswRedisVectorStore}：扫 Redis {@code metadata:*} + {@code segment:}</li>
     *   <li>{@code QdrantVectorStore}：用 Qdrant scroll（filter 下推）拉 payload（含 content）</li>
     * </ul>
     * 返回 chunk 级 id → 映射（内含全部元数据键 + 一个 {@code content} 键为原文）。
     */
    Map<String, Map<String, Object>> scanChunks(Map<String, Object> filters);
}
