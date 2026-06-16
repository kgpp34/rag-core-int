package com.cffex.rag.retrievalengine.domain;

import java.util.Map;
import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.model.ScoredCandidate;

/** 检索阶段候选块实体，承载融合排序前的原始打分信息。 */
public record RetrievalCandidate(
        String chunkId,
        String documentId,
        String knowledgeBaseId,
        String content,
        double vectorScore,
        Double sparseScore,
        Map<String, Object> metadata
) {
    public RetrievalCandidate {
        chunkId = Objects.requireNonNull(chunkId, "chunkId must not be null");
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }

    /** 根据权重计算融合分，用于“权重融合排序”策略。 */
    public double weightedScore(double vectorWeight, double sparseWeight) {
        return vectorScore * vectorWeight + (sparseScore == null ? 0.0d : sparseScore * sparseWeight);
    }

    /** 返回候选块在当前检索域内的稳定唯一键。 */
    public String candidateKey() {
        return knowledgeBaseId + ":" + chunkId;
    }

    /**
     * 兼容模型下，vectorScore 仍承载“当前阶段最终分”。
     */
    public double finalScore() {
        return vectorScore;
    }

    public ScoredCandidate toScoredCandidate() {
        return ScoredCandidate.fromLegacy(this);
    }

    public static RetrievalCandidate fromScoredCandidate(ScoredCandidate candidate) {
        return candidate.toLegacyCandidate();
    }
}
