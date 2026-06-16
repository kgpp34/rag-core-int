package com.cffex.rag.retrievalengine.application.command;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;

/**
 * 检索执行应用命令。
 *
 * <p>它是 retrieval-engine 的内部输入模型，用来阻断
 * {@code rag-common} 的 RetrievalPlan 继续深入应用执行链。
 */
public record ExecuteRetrievalCommand(
        String queryText,
        EmbeddingModelCommand embeddingModel,
        List<KnowledgeBaseRecallCommand> recallCommands,
        GlobalRankingCommand globalRanking,
        int topK,
        boolean globalScoreThresholdEnabled,
        double globalScoreThreshold
) {

    public ExecuteRetrievalCommand {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        recallCommands = List.copyOf(Objects.requireNonNull(recallCommands, "recallCommands must not be null"));
        Objects.requireNonNull(globalRanking, "globalRanking must not be null");
    }

    public GlobalRankingPolicy globalRankingPolicy() {
        return globalRanking.toPolicy(topK, globalScoreThresholdEnabled, globalScoreThreshold);
    }
}
