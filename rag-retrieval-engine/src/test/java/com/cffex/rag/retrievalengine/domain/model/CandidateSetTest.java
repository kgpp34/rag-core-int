package com.cffex.rag.retrievalengine.domain.model;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

class CandidateSetTest {

    @Test
    void fuse_mergesDenseAndSparseScoresIntoFinalScore() {
        CandidateSet dense = CandidateSet.fromLegacy(List.of(
                candidate("chunk-shared", 0.9d, null),
                candidate("chunk-dense-only", 0.3d, null)
        ));
        CandidateSet sparse = CandidateSet.fromLegacy(List.of(
                candidate("chunk-shared", 0.0d, 0.8d),
                candidate("chunk-sparse-only", 0.0d, 0.4d)
        ));

        List<RetrievalCandidate> fused = dense.fuse(sparse, 0.5d, 0.5d).toLegacyCandidates();

        assertThat(fused)
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::finalScore, RetrievalCandidate::sparseScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-shared", 1.0d, 0.8d),
                        org.assertj.core.groups.Tuple.tuple("chunk-dense-only", 0.0d, null),
                        org.assertj.core.groups.Tuple.tuple("chunk-sparse-only", 0.0d, 0.4d)
                );
    }

    @Test
    void filterByFinalScoreThreshold_usesCurrentStageFinalScore() {
        CandidateSet candidateSet = CandidateSet.fromLegacy(List.of(
                candidate("chunk-a", 0.8d, null),
                candidate("chunk-b", 0.3d, null)
        ));

        List<RetrievalCandidate> filtered = candidateSet
                .filterByFinalScoreThreshold(0.5d)
                .toLegacyCandidates();

        assertThat(filtered).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-a");
    }

    @Test
    void mergeByDocId_keepsFirstCandidateForSharedDocWithoutScoreFusion() {
        CandidateSet dense = CandidateSet.fromLegacy(List.of(
                candidate("chunk-shared-a", "doc-shared", 0.9d, null),
                candidate("chunk-dense-only", "doc-dense", 0.3d, null)
        ));
        CandidateSet sparse = CandidateSet.fromLegacy(List.of(
                candidate("chunk-shared-b", "doc-shared", 0.95d, 0.8d),
                candidate("chunk-sparse-only", "doc-sparse", 0.0d, 0.4d)
        ));

        List<RetrievalCandidate> merged = dense.mergeByDocId(sparse).toLegacyCandidates();

        assertThat(merged)
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::documentId, RetrievalCandidate::finalScore, RetrievalCandidate::sparseScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-shared-a", "doc-1", 0.9d, null),
                        org.assertj.core.groups.Tuple.tuple("chunk-dense-only", "doc-1", 0.3d, null),
                        org.assertj.core.groups.Tuple.tuple("chunk-sparse-only", "doc-1", 0.0d, 0.4d)
                );
    }

    private RetrievalCandidate candidate(String chunkId, double finalScore, Double sparseScore) {
        return candidate(chunkId, chunkId, finalScore, sparseScore);
    }

    private RetrievalCandidate candidate(String chunkId, String docId, double finalScore, Double sparseScore) {
        return new RetrievalCandidate(
                chunkId,
                "doc-1",
                "kb-1",
                "content",
                finalScore,
                sparseScore,
                Map.of("doc_id", docId)
        );
    }
}
