package com.cffex.rag.retrievalengine.domain;

import java.util.List;
import java.util.Objects;

/**
 * 全文（BM25/稀疏向量）检索请求。
 *
 * @param knowledgeBaseId 逻辑知识库 ID，用于给候选结果打上来源标记
 * @param collectionName  向量库中的目标 collection 名称
 * @param docIds          文档过滤范围，空集合表示不过滤
 * @param queryText       原始查询文本（交由 BM25/全文检索处理）
 * @param candidateK      召回候选数量（排序前候选池大小）
 * @param scoreThresholdEnabled 是否启用与 Dify 对齐的 adapter 层分数过滤
 * @param scoreThreshold 检索返回分数阈值
 */
public record FullTextSearchRequest(
        String knowledgeBaseId,
        String engineId,
        String collectionName,
        List<String> docIds,
        String queryText,
        int candidateK,
        boolean scoreThresholdEnabled,
        double scoreThreshold
) implements StorageSearchRequest {

    public FullTextSearchRequest {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(engineId, "engineId must not be null");
        Objects.requireNonNull(collectionName, "collectionName must not be null");
        docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds must not be null"));
        Objects.requireNonNull(queryText, "queryText must not be null");
    }

    public FullTextSearchRequest(
            String knowledgeBaseId,
            String collectionName,
            List<String> docIds,
            String queryText,
            int candidateK
    ) {
        this(knowledgeBaseId, "milvus", collectionName, docIds, queryText, candidateK, false, 0.0d);
    }

    public FullTextSearchRequest(
            String knowledgeBaseId,
            String engineId,
            String collectionName,
            List<String> docIds,
            String queryText,
            int candidateK
    ) {
        this(knowledgeBaseId, engineId, collectionName, docIds, queryText, candidateK, false, 0.0d);
    }
}
