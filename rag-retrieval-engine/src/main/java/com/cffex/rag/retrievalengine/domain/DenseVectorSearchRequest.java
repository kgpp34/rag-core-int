package com.cffex.rag.retrievalengine.domain;

import java.util.List;
import java.util.Objects;

/**
 * Dense 向量检索请求（ANN）。
 *
 * @param knowledgeBaseId 逻辑知识库 ID，用于给候选结果打上来源标记
 * @param collectionName  向量库中的目标 collection 名称
 * @param docIds          文档过滤范围，空集合表示不过滤（与全文检索对齐）
 * @param queryVector     检索前已完成向量化的查询向量
 * @param candidateK      召回候选数量（排序前候选池大小）
 * @param scoreThresholdEnabled 是否启用与 Dify 对齐的 adapter 层分数过滤
 * @param scoreThreshold 检索返回分数阈值
 */
public record DenseVectorSearchRequest(
        String knowledgeBaseId,
        String engineId,
        String collectionName,
        List<String> docIds,
        float[] queryVector,
        int candidateK,
        boolean scoreThresholdEnabled,
        double scoreThreshold
) implements StorageSearchRequest {

    public DenseVectorSearchRequest {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(engineId, "engineId must not be null");
        Objects.requireNonNull(collectionName, "collectionName must not be null");
        docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds must not be null"));
        queryVector = Objects.requireNonNull(queryVector, "queryVector must not be null").clone();
    }

    public DenseVectorSearchRequest(
            String knowledgeBaseId,
            String collectionName,
            List<String> docIds,
            float[] queryVector,
            int candidateK
    ) {
        this(knowledgeBaseId, "milvus", collectionName, docIds, queryVector, candidateK, false, 0.0d);
    }

    public DenseVectorSearchRequest(
            String knowledgeBaseId,
            String engineId,
            String collectionName,
            List<String> docIds,
            float[] queryVector,
            int candidateK
    ) {
        this(knowledgeBaseId, engineId, collectionName, docIds, queryVector, candidateK, false, 0.0d);
    }

    @Override
    public float[] queryVector() {
        return queryVector.clone();
    }
}
