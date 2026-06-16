package com.cffex.rag.retrievalengine.application.command;

import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;

/**
 * 请求级排序内部命令对象。
 */
public sealed interface GlobalRankingCommand
        permits WeightedGlobalRankingCommand, RerankGlobalRankingCommand {

    GlobalRankingPolicy toPolicy(int topK, boolean scoreThresholdEnabled, double scoreThreshold);
}
