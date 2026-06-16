package com.cffex.rag.retrievalengine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedRankingServiceTest {

    @Test
    void rank_usesFinalVectorScoreInsteadOfRecombiningSparseScore() {
        WeightedRankingService service = new WeightedRankingService();
        RetrievalCandidate higherFinalScore = candidate("chunk-1", 0.8, 0.0);
        RetrievalCandidate staleSparseScore = candidate("chunk-2", 0.6, 1.0);

        List<RetrievedChunk> ranked = service.rank(
                "q",
                List.of(staleSparseScore, higherFinalScore),
                new WeightedGlobalRankingCommand(0.2d, 0.8d),
                2
        );

        assertThat(ranked)
                .extracting(RetrievedChunk::chunkId, RetrievedChunk::rankingScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-1", 0.8d),
                        org.assertj.core.groups.Tuple.tuple("chunk-2", 0.6d)
                );
    }

    private RetrievalCandidate candidate(String chunkId, double vectorScore, Double sparseScore) {
        return new RetrievalCandidate(chunkId, "doc-1", "kb-1", "content", vectorScore, sparseScore, Map.of());
    }
}
