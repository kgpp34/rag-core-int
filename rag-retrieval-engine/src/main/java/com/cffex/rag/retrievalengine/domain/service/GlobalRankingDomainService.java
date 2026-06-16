package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;
import com.cffex.rag.retrievalengine.domain.model.GlobalRerankPolicy;
import com.cffex.rag.retrievalengine.domain.model.WeightedRankingPolicy;

/**
 * 请求级排序领域服务。
 */
public class GlobalRankingDomainService {

    @FunctionalInterface
    public interface WeightedRankingExecutor {
        List<RetrievedChunk> rank(String query, List<RetrievalCandidate> candidates, WeightedRankingPolicy policy);
    }

    @FunctionalInterface
    public interface GlobalRerankExecutor {
        List<RetrievedChunk> rank(
                String query,
                List<RetrievalCandidate> candidates,
                GlobalRerankPolicy policy,
                int rerankTopN
        );
    }

    public List<RetrievedChunk> rank(
            String query,
            List<RetrievalCandidate> candidates,
            GlobalRankingPolicy policy,
            WeightedRankingExecutor weightedRankingExecutor,
            GlobalRerankExecutor globalRerankExecutor
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(weightedRankingExecutor, "weightedRankingExecutor must not be null");
        Objects.requireNonNull(globalRerankExecutor, "globalRerankExecutor must not be null");

        List<RetrievedChunk> rankedChunks = switch (policy) {
            case WeightedRankingPolicy weightedPolicy ->
                    weightedRankingExecutor.rank(query, candidates, weightedPolicy);
            case GlobalRerankPolicy rerankPolicy ->
                    globalRerankExecutor.rank(
                            query,
                            candidates,
                            rerankPolicy,
                            rerankPolicy.rerankTopN(candidates.size())
                    );
        };
        return postProcess(policy, rankedChunks);
    }

    public List<RetrievedChunk> postProcess(GlobalRankingPolicy policy, List<RetrievedChunk> rankedChunks) {
        if (policy instanceof GlobalRerankPolicy) {
            return rankedChunks;
        }
        return rankedChunks.stream()
                .filter(chunk -> !policy.scoreThresholdEnabled() || chunk.rankingScore() >= policy.scoreThreshold())
                .limit(policy.topK())
                .toList();
    }
}
