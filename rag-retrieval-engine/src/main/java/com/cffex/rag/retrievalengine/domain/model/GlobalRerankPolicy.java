package com.cffex.rag.retrievalengine.domain.model;

import java.util.Objects;

/**
 * 请求级全局重排策略。
 */
public record GlobalRerankPolicy(
        RerankModelPolicy rerankPolicy,
        int topK,
        boolean scoreThresholdEnabled,
        double scoreThreshold
) implements GlobalRankingPolicy {

    public GlobalRerankPolicy {
        Objects.requireNonNull(rerankPolicy, "rerankPolicy must not be null");
    }

}
