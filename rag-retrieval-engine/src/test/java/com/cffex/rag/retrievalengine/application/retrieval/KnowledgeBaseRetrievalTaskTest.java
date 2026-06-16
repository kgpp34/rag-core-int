package com.cffex.rag.retrievalengine.application.retrieval;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.application.RerankRankingService;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.port.ParallelExecutionStrategy;
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.port.FullTextSearchPort;
import com.cffex.rag.retrievalengine.domain.port.VectorSearchPort;
import com.cffex.rag.retrievalengine.domain.service.CandidateFusionDomainService;
import com.cffex.rag.retrievalengine.domain.service.KnowledgeBaseRecallDomainService;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseRetrievalTaskTest {

    private static final float[] QUERY_VECTOR = new float[]{0.1f, 0.2f};

    private static final ParallelExecutionStrategy SYNC = new ParallelExecutionStrategy() {
        @Override
        public <T> List<T> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            return tasks.stream().map(task -> {
                try {
                    return task.call();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).toList();
        }
    };

    @Mock
    private VectorSearchPort vectorPort;

    @Mock
    private FullTextSearchPort fullTextPort;

    @Mock
    private RerankRankingService rerankRankingService;

    @Mock
    private RetrieverRegistry retrieverRegistry;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    private KnowledgeBaseRetrievalTask task;

    @BeforeEach
    void setUp() {
        task = new KnowledgeBaseRetrievalTask(
                retrieverRegistry,
                SYNC,
                rerankRankingService,
                new KnowledgeBaseRecallDomainService(new CandidateFusionDomainService()),
                debugTraceWriter
        );
        org.mockito.Mockito.lenient().when(retrieverRegistry.vectorSearchPort("milvus")).thenReturn(vectorPort);
        org.mockito.Mockito.lenient().when(retrieverRegistry.fullTextSearchPort("milvus")).thenReturn(fullTextPort);
    }

    @Test
    void execute_hybridMode_dispatchesDenseAndFullTextAndMergesResults() {
        when(vectorPort.search(isA(DenseVectorSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-dense", 0.9, null)
        ));
        when(fullTextPort.search(isA(FullTextSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-sparse", 0.0, 0.9)
        ));

        List<RetrievalCandidate> merged = task.execute(
                "search text",
                QUERY_VECTOR,
                hybridRecallCommand()
        );
        ArgumentCaptor<DenseVectorSearchRequest> denseRequestCaptor = ArgumentCaptor.forClass(DenseVectorSearchRequest.class);

        assertThat(merged).extracting(RetrievalCandidate::chunkId)
                .containsExactlyInAnyOrder("chunk-dense", "chunk-sparse");
        verify(vectorPort).search(denseRequestCaptor.capture());
        verify(fullTextPort).search(isA(FullTextSearchRequest.class));
        assertThat(denseRequestCaptor.getValue().queryVector()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void execute_hybridMode_passesScoreThresholdOnlyToDenseRouteLikeDify() {
        when(vectorPort.search(isA(DenseVectorSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-dense", 0.9, null)
        ));
        when(fullTextPort.search(isA(FullTextSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-sparse", 0.0, 0.5)
        ));

        task.execute(
                "search text",
                QUERY_VECTOR,
                hybridRecallCommandWithScoreThreshold()
        );

        ArgumentCaptor<DenseVectorSearchRequest> denseRequestCaptor =
                ArgumentCaptor.forClass(DenseVectorSearchRequest.class);
        ArgumentCaptor<FullTextSearchRequest> fullTextRequestCaptor =
                ArgumentCaptor.forClass(FullTextSearchRequest.class);

        verify(vectorPort).search(denseRequestCaptor.capture());
        verify(fullTextPort).search(fullTextRequestCaptor.capture());
        assertThat(denseRequestCaptor.getValue().scoreThresholdEnabled()).isTrue();
        assertThat(denseRequestCaptor.getValue().scoreThreshold()).isEqualTo(0.7d);
        assertThat(fullTextRequestCaptor.getValue().scoreThresholdEnabled()).isFalse();
        assertThat(fullTextRequestCaptor.getValue().scoreThreshold()).isEqualTo(0.0d);
    }

    @Test
    void execute_fullTextMode_appliesKnowledgeBaseRerankAfterSparseRecall() {
        when(fullTextPort.search(isA(FullTextSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-sparse-a", 0.0, 0.8),
                candidate("chunk-sparse-b", 0.0, 0.6)
        ));
        when(rerankRankingService.rerankCandidates(
                "search text",
                List.of(
                        candidate("chunk-sparse-a", 0.0, 0.8),
                        candidate("chunk-sparse-b", 0.0, 0.6)
                ),
                rerankCommand(),
                2,
                0.0d
        )).thenReturn(List.of(
                candidate("chunk-sparse-b", 0.95, 0.6),
                candidate("chunk-sparse-a", 0.4, 0.8)
        ));

        List<RetrievalCandidate> merged = task.execute(
                "search text",
                null,
                fullTextRecallCommandWithRerank()
        );

        assertThat(merged).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-sparse-b", "chunk-sparse-a");
        verify(vectorPort, never()).search(isA(DenseVectorSearchRequest.class));
    }

    @Test
    void execute_hybridModeWithRerank_deduplicatesRoutesBeforeRerankWithoutNormalizedFusion() {
        when(vectorPort.search(isA(DenseVectorSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-shared", 0.9, null),
                candidate("chunk-dense", 0.4, null)
        ));
        when(fullTextPort.search(isA(FullTextSearchRequest.class))).thenReturn(List.of(
                candidate("chunk-shared", 0.0, 0.8),
                candidate("chunk-sparse", 0.0, 0.6)
        ));
        when(rerankRankingService.rerankCandidates(
                eq("search text"),
                eq(List.of(
                        candidate("chunk-shared", 0.9, null),
                        candidate("chunk-dense", 0.4, null),
                        candidate("chunk-sparse", 0.0, 0.6)
                )),
                eq(rerankCommand()),
                eq(3),
                eq(0.0d)
        )).thenReturn(List.of(
                candidate("chunk-sparse", 0.95, 0.6),
                candidate("chunk-shared", 0.85, 0.8),
                candidate("chunk-dense", 0.7, null)
        ));

        List<RetrievalCandidate> merged = task.execute(
                "search text",
                QUERY_VECTOR,
                hybridRecallCommandWithRerank()
        );

        assertThat(merged).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-sparse", "chunk-shared", "chunk-dense");
    }

    private RetrievalCandidate candidate(String chunkId, double vectorScore, Double sparseScore) {
        return new RetrievalCandidate(chunkId, "doc-1", "kb-1", "content", vectorScore, sparseScore, Map.of());
    }

    private KnowledgeBaseRecallCommand hybridRecallCommand() {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "kb-1-collection",
                RetrievalMode.HYBRID,
                binding(),
                binding(),
                List.of(),
                20,
                null,
                false,
                false,
                0.0d,
                0.5d,
                0.5d,
                null
        );
    }

    private KnowledgeBaseRecallCommand fullTextRecallCommandWithRerank() {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "kb-1-collection",
                RetrievalMode.FULL_TEXT,
                binding(),
                binding(),
                List.of(),
                20,
                2,
                true,
                false,
                0.0d,
                0.7d,
                0.3d,
                rerankCommand()
        );
    }

    private KnowledgeBaseRecallCommand hybridRecallCommandWithRerank() {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "kb-1-collection",
                RetrievalMode.HYBRID,
                binding(),
                binding(),
                List.of(),
                20,
                3,
                true,
                false,
                0.0d,
                0.5d,
                0.5d,
                rerankCommand()
        );
    }

    private KnowledgeBaseRecallCommand hybridRecallCommandWithScoreThreshold() {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "kb-1-collection",
                RetrievalMode.HYBRID,
                binding(),
                binding(),
                List.of(),
                20,
                null,
                false,
                true,
                0.7d,
                0.5d,
                0.5d,
                null
        );
    }

    private RerankGlobalRankingCommand rerankCommand() {
        return new RerankGlobalRankingCommand(
                new ModelEndpointCommand("https://rerank.example.com", "token", "rerank-model")
        );
    }

    private SearchBindingCommand binding() {
        return new SearchBindingCommand("milvus", "kb-1-collection", Map.of());
    }
}
