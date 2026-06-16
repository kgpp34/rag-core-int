package com.cffex.rag.common.domain.retrieval;

import java.util.Map;
import java.util.Objects;

/**
 * 检索结果块：排序完成后对外暴露的最终结果单元。
 *
 * <p>分数语义：
 * <ul>
 *   <li>{@code vectorScore} — dense 向量召回分，始终存在</li>
 *   <li>{@code sparseScore} — 稀疏/全文召回分，仅 HYBRID 模式下非 null</li>
 *   <li>{@code rankingScore} — 最终排序所用分数：权重融合时为加权和，rerank 时为模型打分</li>
 * </ul>
 * 调用方按 {@code rankingScore} 降序展示即可，无需理解内部排序逻辑。
 */
public record RetrievedChunk(
        String chunkId,
        String documentId,
        String knowledgeBaseId,
        double vectorScore,
        Double sparseScore,
        double rankingScore,
        String content,
        Map<String, Object> metadata
) {
    public RetrievedChunk {
        chunkId = Objects.requireNonNull(chunkId, "chunkId must not be null");
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
