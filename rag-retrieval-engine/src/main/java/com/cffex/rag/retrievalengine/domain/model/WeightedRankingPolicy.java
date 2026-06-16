package com.cffex.rag.retrievalengine.domain.model;

/**
 * 请求级 weighted 排序策略。
 */
public record WeightedRankingPolicy(
        double vectorWeight,
        double keywordWeight,
        int topK,
        boolean scoreThresholdEnabled,
        double scoreThreshold
) implements GlobalRankingPolicy {

    public WeightedRankingPolicy(double vectorWeight, double keywordWeight, int topK) {
        this(vectorWeight, keywordWeight, topK, false, 0.0d);
    }
}
