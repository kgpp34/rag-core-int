package com.cffex.rag.retrievalengine.domain.model;

/**
 * 请求级排序策略。
 */
public sealed interface GlobalRankingPolicy
        permits WeightedRankingPolicy, GlobalRerankPolicy {

    int topK();

    boolean scoreThresholdEnabled();

    double scoreThreshold();

    default int rerankTopN(int candidateCount) {
        return topK();
    }
}
