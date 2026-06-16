package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.GlobalRerankPolicy;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;
import com.cffex.rag.retrievalengine.domain.model.WeightedRankingPolicy;

class GlobalRankingDomainServiceTest {

    private final GlobalRankingDomainService service = new GlobalRankingDomainService();

    @Test
    void rank_weightedPolicy_routesToWeightedExecutor() {
        List<RetrievalCandidate> candidates = List.of(candidate("chunk-1", 0.9d));
        List<RetrievedChunk> chunks = List.of(chunk("chunk-1", 0.9d));
        AtomicBoolean weightedCalled = new AtomicBoolean(false);
        AtomicBoolean rerankCalled = new AtomicBoolean(false);

        List<RetrievedChunk> result = service.rank(
                "q",
                candidates,
                new WeightedRankingPolicy(0.5d, 0.5d, 1),
                (query, candidatesToRank, policy) -> {
                    weightedCalled.set(true);
                    assertThat(query).isEqualTo("q");
                    assertThat(candidatesToRank).isSameAs(candidates);
                    assertThat(policy).isEqualTo(new WeightedRankingPolicy(0.5d, 0.5d, 1));
                    return chunks;
                },
                (query, candidatesToRank, policy, topN) -> {
                    rerankCalled.set(true);
                    return List.of();
                }
        );

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-1");
        assertThat(weightedCalled).isTrue();
        assertThat(rerankCalled).isFalse();
    }

    @Test
    void rank_globalRerankPolicy_routesToRerankExecutorWithoutPostProcess() {
        List<RetrievalCandidate> candidates = List.of(
                candidate("chunk-1", 0.9d),
                candidate("chunk-2", 0.7d),
                candidate("chunk-3", 0.4d)
        );
        GlobalRerankPolicy policy = new GlobalRerankPolicy(
                new RerankModelPolicy("http://rerank", "token", "model"),
                2,
                true,
                0.5d
        );
        List<RetrievedChunk> chunks = List.of(
                chunk("chunk-1", 0.95d),
                chunk("chunk-2", 0.60d),
                chunk("chunk-3", 0.40d)
        );
        AtomicBoolean weightedCalled = new AtomicBoolean(false);
        AtomicBoolean rerankCalled = new AtomicBoolean(false);

        List<RetrievedChunk> result = service.rank(
                "q",
                candidates,
                policy,
                (query, candidatesToRank, weightedPolicy) -> {
                    weightedCalled.set(true);
                    return List.of();
                },
                (query, candidatesToRank, rerankPolicy, topN) -> {
                    rerankCalled.set(true);
                    assertThat(query).isEqualTo("q");
                    assertThat(candidatesToRank).isSameAs(candidates);
                    assertThat(rerankPolicy).isEqualTo(policy);
                    assertThat(topN).isEqualTo(2);
                    return chunks;
                }
        );

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-1", "chunk-2", "chunk-3");
        assertThat(weightedCalled).isFalse();
        assertThat(rerankCalled).isTrue();
    }

    @Test
    void postProcess_globalRerankPolicy_returnsRerankOutputDirectlyLikeDify() {
        List<RetrievedChunk> chunks = List.of(
                chunk("chunk-1", 0.95d),
                chunk("chunk-2", 0.60d),
                chunk("chunk-3", 0.40d)
        );

        List<RetrievedChunk> result = service.postProcess(
                new GlobalRerankPolicy(
                        new RerankModelPolicy("http://rerank", "token", "model"),
                        2,
                        true,
                        0.5d
                ),
                chunks
        );

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-1", "chunk-2", "chunk-3");
    }

    @Test
    void postProcess_weightedPolicy_appliesThresholdAndTopK() {
        List<RetrievedChunk> chunks = List.of(
                chunk("chunk-1", 0.95d),
                chunk("chunk-2", 0.60d),
                chunk("chunk-3", 0.40d)
        );

        List<RetrievedChunk> result = service.postProcess(
                new WeightedRankingPolicy(0.5d, 0.5d, 2, true, 0.5d),
                chunks
        );

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-1", "chunk-2");
    }

    private RetrievedChunk chunk(String chunkId, double rankingScore) {
        return new RetrievedChunk(
                chunkId,
                "doc-1",
                "kb-1",
                rankingScore,
                0.0d,
                rankingScore,
                "content",
                Map.of()
        );
    }

    private RetrievalCandidate candidate(String chunkId, double finalScore) {
        return new RetrievalCandidate(chunkId, "doc-1", "kb-1", "content", finalScore, 0.0d, Map.of());
    }
}
