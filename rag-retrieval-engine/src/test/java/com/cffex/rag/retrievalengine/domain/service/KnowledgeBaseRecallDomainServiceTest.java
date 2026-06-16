package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;

class KnowledgeBaseRecallDomainServiceTest {

    private final KnowledgeBaseRecallDomainService service = new KnowledgeBaseRecallDomainService(
            new CandidateFusionDomainService()
    );

    @Test
    void recall_withoutRerank_preservesMergedCandidates() {
        KnowledgeBaseRecallPolicy policy = new KnowledgeBaseRecallPolicy(
                RetrievalMode.HYBRID,
                10,
                5,
                false,
                0.0d,
                0.5d,
                0.5d,
                null
        );

        List<RetrievalCandidate> candidates = service.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(
                        List.of(candidate("chunk-dense", 0.9d, null)),
                        List.of(candidate("chunk-sparse", 0.0d, 0.8d))
                ),
                merged -> {
                    throw new AssertionError("unexpected rerank");
                }
        );

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-dense", "chunk-sparse");
    }

    @Test
    void recall_withRerank_returnsRerankOutputWithoutLocalPostProcess() {
        KnowledgeBaseRecallPolicy policy = new KnowledgeBaseRecallPolicy(
                RetrievalMode.FULL_TEXT,
                10,
                1,
                false,
                0.0d,
                0.0d,
                1.0d,
                new RerankModelPolicy("http://rerank", "token", "model")
        );

        List<RetrievalCandidate> candidates = service.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(
                        List.of(),
                        List.of(
                                candidate("chunk-a", 0.0d, 0.8d),
                                candidate("chunk-b", 0.0d, 0.6d)
                        )
                ),
                merged -> List.of(
                        candidate("chunk-b", 0.95d, 0.6d),
                        candidate("chunk-a", 0.40d, 0.8d)
                )
        );

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-b", "chunk-a");
    }

    @Test
    void recall_withRerank_doesNotApplyKnowledgeBaseThresholdAfterRerank() {
        KnowledgeBaseRecallPolicy policy = new KnowledgeBaseRecallPolicy(
                RetrievalMode.FULL_TEXT,
                10,
                5,
                true,
                0.7d,
                0.0d,
                1.0d,
                new RerankModelPolicy("http://rerank", "token", "model")
        );

        List<RetrievalCandidate> candidates = service.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(
                        List.of(),
                        List.of(candidate("chunk-a", 0.0d, 0.8d))
                ),
                merged -> List.of(candidate("chunk-a", 0.40d, 0.8d))
        );

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-a");
    }

    @Test
    void recall_hybridWithRerank_mergesByChunkIdBeforeRerankWithoutNormalization() {
        KnowledgeBaseRecallPolicy policy = new KnowledgeBaseRecallPolicy(
                RetrievalMode.HYBRID,
                10,
                10,
                false,
                0.0d,
                0.5d,
                0.5d,
                new RerankModelPolicy("http://rerank", "token", "model")
        );
        AtomicReference<List<RetrievalCandidate>> rerankInput = new AtomicReference<>();

        List<RetrievalCandidate> candidates = service.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(
                        List.of(
                                candidate("chunk-shared", 0.9d, null),
                                candidate("chunk-dense", 0.4d, null)
                        ),
                        List.of(
                                candidate("chunk-shared", 0.0d, 0.8d),
                                candidate("chunk-sparse", 0.0d, 0.6d)
                        )
                ),
                merged -> {
                    rerankInput.set(merged);
                    return List.of(
                            candidate("chunk-sparse", 0.95d, 0.6d),
                            candidate("chunk-shared", 0.85d, 0.8d),
                            candidate("chunk-dense", 0.70d, null)
                    );
                }
        );

        assertThat(rerankInput.get())
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::finalScore, RetrievalCandidate::sparseScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-shared", 0.9d, null),
                        org.assertj.core.groups.Tuple.tuple("chunk-dense", 0.4d, null),
                        org.assertj.core.groups.Tuple.tuple("chunk-sparse", 0.0d, 0.6d)
                );
        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-sparse", "chunk-shared", "chunk-dense");
    }

    @Test
    void recall_filtersSparseCandidatesByStrictKnowledgeBaseThreshold() {
        KnowledgeBaseRecallPolicy policy = new KnowledgeBaseRecallPolicy(
                RetrievalMode.FULL_TEXT,
                10,
                10,
                true,
                0.7d,
                0.0d,
                1.0d,
                null
        );

        List<RetrievalCandidate> candidates = service.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(
                        List.of(),
                        List.of(
                                candidate("chunk-high", 0.0d, 0.8d),
                                candidate("chunk-equal", 0.0d, 0.7d),
                                candidate("chunk-low", 0.0d, 0.6d)
                        )
                ),
                merged -> {
                    throw new AssertionError("unexpected rerank");
                }
        );

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-high");
    }

    private RetrievalCandidate candidate(String chunkId, double finalScore, Double sparseScore) {
        return new RetrievalCandidate(chunkId, "doc-1", "kb-1", "content", finalScore, sparseScore, Map.of());
    }
}
