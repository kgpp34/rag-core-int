package com.cffex.rag.retrievalengine.application.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;

/**
 * 检索执行输出。
 */
public record RetrievalExecutionResult(
        List<RetrievedChunk> chunks,
        int candidateCount,
        String rankingStrategy,
        String rankingMode,
        boolean globalRerankEnabled,
        boolean globalScoreThresholdEnabled,
        double globalScoreThreshold,
        boolean weightedFallback,
        long knowledgeBaseRerankCount,
        int postRankingCount
) {

    private static final String DENSE_RETRIEVAL_PROVIDER = "spring_ai_rag";

    public RetrievalExecutionResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        Objects.requireNonNull(rankingStrategy, "rankingStrategy must not be null");
        Objects.requireNonNull(rankingMode, "rankingMode must not be null");
    }

    public Map<String, Object> toDebugTrace() {
        return Map.of(
                "candidate_count", candidateCount,
                "ranking_strategy", rankingStrategy,
                "ranking_mode", rankingMode,
                "dense_retrieval_provider", DENSE_RETRIEVAL_PROVIDER,
                "global_rerank_enabled", globalRerankEnabled,
                "global_score_threshold_enabled", globalScoreThresholdEnabled,
                "global_score_threshold", globalScoreThreshold,
                "weighted_fallback", weightedFallback,
                "knowledge_base_rerank_count", knowledgeBaseRerankCount,
                "post_ranking_count", postRankingCount
        );
    }
}
