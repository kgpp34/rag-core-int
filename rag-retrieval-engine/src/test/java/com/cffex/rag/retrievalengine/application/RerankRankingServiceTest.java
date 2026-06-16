package com.cffex.rag.retrievalengine.application;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.RerankPort;

class RerankRankingServiceTest {

    @Test
    void rank_sameChunkIdAcrossKnowledgeBases_usesCandidateKeyScores() {
        CapturingRerankPort rerankPort = new CapturingRerankPort();
        RerankRankingService service = new RerankRankingService(rerankPort);
        RetrievalCandidate kb1 = candidate("chunk-1", "kb-1", 0.7);
        RetrievalCandidate kb2 = candidate("chunk-1", "kb-2", 0.6);
        RerankModelPolicy portPolicy = new RerankModelPolicy("https://rerank.example.com", "token", "rerank-model");
        RerankGlobalRankingCommand spec = new RerankGlobalRankingCommand(
                new ModelEndpointCommand("https://rerank.example.com", "token", "rerank-model"));

        rerankPort.scores = Map.of(
                kb1.candidateKey(), 0.2,
                kb2.candidateKey(), 0.9
        );

        List<RetrievedChunk> ranked = service.rank("q", List.of(kb1, kb2), spec, 2);

        assertThat(rerankPort.query).isEqualTo("q");
        assertThat(rerankPort.policy).isEqualTo(portPolicy);
        assertThat(rerankPort.candidates).containsExactly(kb1, kb2);
        assertThat(rerankPort.topN).isEqualTo(2);
        assertThat(rerankPort.scoreThreshold).isEqualTo(0.0d);
        assertThat(ranked)
                .extracting(RetrievedChunk::knowledgeBaseId, RetrievedChunk::rankingScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("kb-2", 0.9),
                        org.assertj.core.groups.Tuple.tuple("kb-1", 0.2)
                );
    }

    @Test
    void rerankCandidates_rewritesVectorScoreWithRerankScoreAndKeepsSparseScore() {
        CapturingRerankPort rerankPort = new CapturingRerankPort();
        RerankRankingService service = new RerankRankingService(rerankPort);
        RetrievalCandidate kb1 = new RetrievalCandidate("chunk-1", "doc-1", "kb-1", "content-1", 0.7, 0.3, Map.of());
        RetrievalCandidate kb2 = new RetrievalCandidate("chunk-2", "doc-1", "kb-1", "content-2", 0.6, 0.9, Map.of());
        RerankGlobalRankingCommand spec = new RerankGlobalRankingCommand(
                new ModelEndpointCommand("https://rerank.example.com", "token", "rerank-model"));

        rerankPort.scores = Map.of(
                kb1.candidateKey(), 0.2,
                kb2.candidateKey(), 0.95
        );

        List<RetrievalCandidate> reranked = service.rerankCandidates("q", List.of(kb1, kb2), spec, 2);

        assertThat(reranked)
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::vectorScore, RetrievalCandidate::sparseScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-2", 0.95, 0.9),
                        org.assertj.core.groups.Tuple.tuple("chunk-1", 0.2, 0.3)
                );
    }

    @Test
    void rerankCandidates_keepsOnlyCandidatesReturnedByRerankService() {
        CapturingRerankPort rerankPort = new CapturingRerankPort();
        RerankRankingService service = new RerankRankingService(rerankPort);
        RetrievalCandidate scored = candidate("chunk-1", "kb-1", 0.7);
        RetrievalCandidate unscored = candidate("chunk-2", "kb-1", 0.6);
        RerankGlobalRankingCommand spec = new RerankGlobalRankingCommand(
                new ModelEndpointCommand("https://rerank.example.com", "token", "rerank-model"));

        rerankPort.scores = Map.of(scored.candidateKey(), 0.2);

        List<RetrievalCandidate> reranked = service.rerankCandidates(
                "q",
                List.of(scored, unscored),
                spec,
                2,
                0.35d
        );

        assertThat(reranked).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-1");
    }

    private RetrievalCandidate candidate(String chunkId, String kbId, double vectorScore) {
        return new RetrievalCandidate(chunkId, "doc-1", kbId, "content", vectorScore, null, Map.of());
    }

    private static final class CapturingRerankPort implements RerankPort {
        private Map<String, Double> scores = Map.of();
        private String query;
        private RerankModelPolicy policy;
        private List<RetrievalCandidate> candidates;
        private int topN;
        private Double scoreThreshold;

        @Override
        public Map<String, Double> rerank(
                String query,
                RerankModelPolicy policy,
                List<RetrievalCandidate> candidates,
                int topN,
                Double scoreThreshold
        ) {
            this.query = query;
            this.policy = policy;
            this.candidates = candidates;
            this.topN = topN;
            this.scoreThreshold = scoreThreshold;
            return scores;
        }
    }
}
