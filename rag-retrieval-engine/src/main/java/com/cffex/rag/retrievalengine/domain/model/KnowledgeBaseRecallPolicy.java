package com.cffex.rag.retrievalengine.domain.model;

import com.cffex.rag.common.domain.metadata.RetrievalMode;

/**
 * 单知识库召回策略。
 */
public record KnowledgeBaseRecallPolicy(
        RetrievalMode retrievalMode,
        int candidateK,
        Integer topK,
        boolean scoreThresholdEnabled,
        double scoreThreshold,
        double vectorWeight,
        double keywordWeight,
        RerankModelPolicy rerankPolicy
) {

    public boolean usesDenseRoute() {
        return retrievalMode == RetrievalMode.SEMANTIC || retrievalMode == RetrievalMode.HYBRID;
    }

    public boolean usesSparseRoute() {
        return retrievalMode == RetrievalMode.FULL_TEXT || retrievalMode == RetrievalMode.HYBRID;
    }

    public boolean hasRerankPolicy() {
        return rerankPolicy != null;
    }

    public int resolvedTopK() {
        if (topK != null && topK > 0) {
            return topK;
        }
        return candidateK;
    }

    public int routeCandidateK() {
        return Math.max(candidateK, resolvedTopK());
    }

    public boolean allowsScore(double score) {
        return !scoreThresholdEnabled || score >= scoreThreshold;
    }

    public double resolvedVectorWeight() {
        return switch (retrievalMode) {
            case SEMANTIC -> 1.0d;
            case FULL_TEXT -> 0.0d;
            case HYBRID -> vectorWeight;
        };
    }

    public double resolvedKeywordWeight() {
        return switch (retrievalMode) {
            case SEMANTIC -> 0.0d;
            case FULL_TEXT -> 1.0d;
            case HYBRID -> keywordWeight;
        };
    }
}
