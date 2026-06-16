package com.cffex.rag.retrievalengine.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

class RetrievalSessionTest {

    @Test
    void session_tracksProcessedQueryAndCandidates() {
        RetrievalSession session = RetrievalSession.start(
                "hello",
                List.of(new KnowledgeBaseRecallPolicy(
                        RetrievalMode.HYBRID,
                        10,
                        5,
                        false,
                        0.0d,
                        0.5d,
                        0.5d,
                        new RerankModelPolicy("http://rerank", "token", "model")
                )),
                new WeightedRankingPolicy(0.5d, 0.5d, 5)
        );

        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "content", 0.9d, 0.4d, Map.of()
        );

        RetrievalSession updated = session
                .withProcessedQueryText("rewritten")
                .withRecalledCandidates(List.of(candidate))
                .withEnrichedCandidates(List.of(candidate));

        assertThat(updated.effectiveQueryText()).isEqualTo("rewritten");
        assertThat(updated.knowledgeBaseRerankCount()).isEqualTo(1);
        assertThat(updated.globalRerankEnabled()).isFalse();
        assertThat(updated.recalledCandidates()).containsExactly(candidate);
        assertThat(updated.enrichedCandidates()).containsExactly(candidate);
    }
}
