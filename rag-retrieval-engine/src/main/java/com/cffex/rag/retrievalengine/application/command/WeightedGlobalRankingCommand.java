package com.cffex.rag.retrievalengine.application.command;

import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;
import com.cffex.rag.retrievalengine.domain.model.WeightedRankingPolicy;

/**
 * 请求级权重融合排序命令。
 */
public record WeightedGlobalRankingCommand(double vectorWeight, double keywordWeight)
        implements GlobalRankingCommand {

    @Override
    public GlobalRankingPolicy toPolicy(int topK, boolean scoreThresholdEnabled, double scoreThreshold) {
        return new WeightedRankingPolicy(vectorWeight, keywordWeight, topK, scoreThresholdEnabled, scoreThreshold);
    }
}
