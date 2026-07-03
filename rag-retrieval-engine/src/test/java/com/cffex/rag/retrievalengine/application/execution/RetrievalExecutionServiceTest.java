package com.cffex.rag.retrievalengine.application.execution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.retrievalengine.application.RerankRankingService;
import com.cffex.rag.retrievalengine.application.WeightedRankingService;
import com.cffex.rag.retrievalengine.application.command.EmbeddingModelCommand;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.query.ProcessedQuery;
import com.cffex.rag.retrievalengine.application.query.QueryPreprocessor;
import com.cffex.rag.retrievalengine.application.retrieval.MultiKnowledgeBaseRecallService;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.QueryEmbeddingPort;
import com.cffex.rag.retrievalengine.domain.service.GlobalRankingDomainService;

@ExtendWith(MockitoExtension.class)
class RetrievalExecutionServiceTest {

    @Mock
    private MetadataQueryService metadataQueryService;

    @Mock
    private QueryPreprocessor queryPreprocessor;

    @Mock
    private MultiKnowledgeBaseRecallService multiKnowledgeBaseRecallService;

    @Mock
    private WeightedRankingService weightedRankingService;

    @Mock
    private RerankRankingService rerankRankingService;

    @Mock
    private QueryEmbeddingPort queryEmbeddingPort;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    @Test
    void execute_weightedPlan_embedsQueryOnceBeforeRecallAndEnrichesMetadata() {
        RetrievalExecutionService executionService = executionService();
        ExecuteRetrievalCommand command = weightedCommand();
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "chunk content", 0.91, 0.44, Map.of("source", "spring-ai")
        );
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.91, 0.44, 0.81, "chunk content",
                Map.of("source", "spring-ai", "document_name", "Document A", "upload_file_id", "file-123"));

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(queryEmbeddingPort.embed("rewritten hello", embeddingPolicy())).thenReturn(new float[]{0.1f, 0.2f});
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(candidate));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "Document A", "file-123", null)
        ));
        when(weightedRankingService.rank(
                "rewritten hello",
                List.of(new RetrievalCandidate(
                        "chunk-1", "doc-1", "kb-1", "chunk content", 0.91, 0.44,
                        Map.of(
                                "source", "spring-ai",
                                "document_name", "Document A",
                                "upload_file_id", "file-123"
                        )
                )),
                new WeightedGlobalRankingCommand(0.5d, 0.5d),
                5
        )).thenReturn(List.of(rankedChunk));

        RetrievalExecutionResult result = executionService.execute(initialContext);
        ArgumentCaptor<RetrievalExecutionContext> contextCaptor = ArgumentCaptor.forClass(RetrievalExecutionContext.class);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        assertThat(result.toDebugTrace())
                .containsEntry("dense_retrieval_provider", "spring_ai_rag")
                .containsEntry("ranking_mode", "weighted_fallback")
                .containsEntry("weighted_fallback", true)
                .containsEntry("candidate_count", 1);
        verify(queryEmbeddingPort).embed("rewritten hello", embeddingPolicy());
        verify(multiKnowledgeBaseRecallService).recall(eq(preprocessedQuery), contextCaptor.capture());
        assertThat(contextCaptor.getValue().queryVector()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void execute_globalRerankPlanWithMultipleDenseKnowledgeBases_embedsQueryOnlyOnce() {
        ExecuteRetrievalCommand command = rerankCommand(List.of(recallCommand(rerankModel()), recallCommand(null)));
        RetrievalExecutionService executionService = executionService();
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "chunk content", 0.91, 0.44, Map.of("source", "spring-ai")
        );
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.91, 0.44, 0.96, "chunk content",
                Map.of("source", "spring-ai", "document_name", "Document A", "upload_file_id", "file-123"));

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(queryEmbeddingPort.embed("rewritten hello", embeddingPolicy())).thenReturn(new float[]{0.1f, 0.2f});
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(candidate));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "Document A", "file-123", null)
        ));
        when(rerankRankingService.rank(
                "rewritten hello",
                List.of(new RetrievalCandidate(
                        "chunk-1", "doc-1", "kb-1", "chunk content", 0.91, 0.44,
                        Map.of(
                                "source", "spring-ai",
                                "document_name", "Document A",
                                "upload_file_id", "file-123"
                        )
                )),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                5,
                0.0d
        )).thenReturn(List.of(rankedChunk));

        RetrievalExecutionResult result = executionService.execute(initialContext);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        assertThat(result.toDebugTrace())
                .containsEntry("dense_retrieval_provider", "spring_ai_rag")
                .containsEntry("ranking_mode", "global_rerank")
                .containsEntry("global_rerank_enabled", true)
                .containsEntry("weighted_fallback", false);
        verify(queryEmbeddingPort).embed("rewritten hello", embeddingPolicy());
    }

    @Test
    void execute_singleKbWithGlobalRerankPolicy_skipsGlobalRerankAndUsesKbRankingResult() {
        ExecuteRetrievalCommand command = rerankCommand();
        RetrievalExecutionService executionService = executionService();
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "chunk content", 0.95, 0.44, Map.of("source", "spring-ai")
        );

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(queryEmbeddingPort.embed("rewritten hello", embeddingPolicy())).thenReturn(new float[]{0.1f, 0.2f});
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(candidate));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of());

        RetrievalExecutionResult result = executionService.execute(initialContext);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(result.chunks().get(0).rankingScore()).isEqualTo(0.95);
        assertThat(result.toDebugTrace())
                .containsEntry("ranking_mode", "single_kb_skip_global_rerank")
                .containsEntry("global_rerank_enabled", false)
                .containsEntry("weighted_fallback", false);
        verify(rerankRankingService, never()).rank(any(), any(), any(), any(int.class), any());
        verify(weightedRankingService, never()).rank(any(), any(), any(), any(int.class));
    }

    @Test
    void execute_singleKbWithoutKbRerankPolicy_runsGlobalRerank() {
        ExecuteRetrievalCommand command = rerankCommand(List.of(recallCommand(null)));
        RetrievalExecutionService executionService = executionService();
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "chunk content", 0.95, 0.44, Map.of("source", "spring-ai")
        );
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.95, 0.44, 0.98, "chunk content",
                Map.of("source", "spring-ai"));

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(queryEmbeddingPort.embed("rewritten hello", embeddingPolicy())).thenReturn(new float[]{0.1f, 0.2f});
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(candidate));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of());
        when(rerankRankingService.rank(
                "rewritten hello",
                List.of(candidate),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                5,
                0.0d
        )).thenReturn(List.of(rankedChunk));

        RetrievalExecutionResult result = executionService.execute(initialContext);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        assertThat(result.toDebugTrace())
                .containsEntry("ranking_mode", "global_rerank")
                .containsEntry("global_rerank_enabled", true)
                .containsEntry("weighted_fallback", false)
                .containsEntry("knowledge_base_rerank_count", 0L);
        verify(rerankRankingService).rank(
                "rewritten hello",
                List.of(candidate),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                5,
                0.0d
        );
        verify(weightedRankingService, never()).rank(any(), any(), any(), any(int.class));
    }

    @Test
    void execute_globalRerankPlan_ordersCandidatesByKbRerankScoreBeforeGlobalRerank() {
        ExecuteRetrievalCommand command = rerankCommand(List.of(recallCommand(null), recallCommand(null)));
        RetrievalExecutionService executionService = executionService();
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate lowScore = new RetrievalCandidate(
                "chunk-low", "doc-low", "kb-1", "low score chunk", 0.36, null, Map.of("source", "spring-ai")
        );
        RetrievalCandidate highScore = new RetrievalCandidate(
                "chunk-high", "doc-high", "kb-2", "high score chunk", 0.55, null, Map.of("source", "spring-ai")
        );
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-high", "doc-high", "kb-2", 0.55, null, 0.91, "high score chunk", Map.of("source", "spring-ai"));

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(queryEmbeddingPort.embed("rewritten hello", embeddingPolicy())).thenReturn(new float[]{0.1f, 0.2f});
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(lowScore, highScore));
        when(metadataQueryService.getDocumentMetas(List.of("doc-low", "doc-high"))).thenReturn(Map.of());
        when(rerankRankingService.rank(
                "rewritten hello",
                List.of(highScore, lowScore),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                5,
                0.0d
        )).thenReturn(List.of(rankedChunk));

        RetrievalExecutionResult result = executionService.execute(initialContext);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        verify(rerankRankingService).rank(
                "rewritten hello",
                List.of(highScore, lowScore),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                5,
                0.0d
        );
    }

    @Test
    void execute_fullTextOnlyPlan_skipsQueryEmbedding() {
        RetrievalExecutionService executionService = executionService();
        ExecuteRetrievalCommand command = weightedCommand(List.of(fullTextRecallCommand()));
        RetrievalExecutionContext initialContext = new RetrievalExecutionContext(command);
        ProcessedQuery preprocessedQuery = new ProcessedQuery("rewritten hello");
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "doc-1", "kb-1", "chunk content", 0.0, 0.44, Map.of("source", "spring-ai")
        );
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.0, 0.44, 0.22, "chunk content",
                Map.of("source", "spring-ai", "document_name", "Document A", "upload_file_id", "file-123"));

        when(queryPreprocessor.preprocess(initialContext)).thenReturn(preprocessedQuery);
        when(multiKnowledgeBaseRecallService.recall(eq(preprocessedQuery), any(RetrievalExecutionContext.class)))
                .thenReturn(List.of(candidate));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "Document A", "file-123", null)
        ));
        when(weightedRankingService.rank(
                "rewritten hello",
                List.of(new RetrievalCandidate(
                        "chunk-1", "doc-1", "kb-1", "chunk content", 0.0, 0.44,
                        Map.of(
                                "source", "spring-ai",
                                "document_name", "Document A",
                                "upload_file_id", "file-123"
                        )
                )),
                new WeightedGlobalRankingCommand(0.5d, 0.5d),
                5
        )).thenReturn(List.of(rankedChunk));

        RetrievalExecutionResult result = executionService.execute(initialContext);
        ArgumentCaptor<RetrievalExecutionContext> contextCaptor = ArgumentCaptor.forClass(RetrievalExecutionContext.class);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        verify(queryEmbeddingPort, never()).embed(any(), any());
        verify(multiKnowledgeBaseRecallService).recall(eq(preprocessedQuery), contextCaptor.capture());
        assertThat(contextCaptor.getValue().queryVector()).isNull();
    }

    private RetrievalExecutionService executionService() {
        return new RetrievalExecutionService(
                queryPreprocessor,
                multiKnowledgeBaseRecallService,
                metadataQueryService,
                weightedRankingService,
                rerankRankingService,
                new GlobalRankingDomainService(),
                queryEmbeddingPort,
                debugTraceWriter
        );
    }

    private ExecuteRetrievalCommand weightedCommand() {
        return weightedCommand(List.of(recallCommand(null)));
    }

    private ExecuteRetrievalCommand weightedCommand(List<KnowledgeBaseRecallCommand> recallCommands) {
        return new ExecuteRetrievalCommand(
                "hello",
                embeddingModel(),
                recallCommands,
                new WeightedGlobalRankingCommand(0.5d, 0.5d),
                5,
                false,
                0.0d
        );
    }

    private ExecuteRetrievalCommand rerankCommand() {
        return rerankCommand(List.of(recallCommand(rerankModel())));
    }

    private ExecuteRetrievalCommand rerankCommand(List<KnowledgeBaseRecallCommand> recallCommands) {
        return new ExecuteRetrievalCommand(
                "hello",
                embeddingModel(),
                recallCommands,
                rerankModel(),
                5,
                false,
                0.0d
        );
    }

    private EmbeddingModelCommand embeddingModel() {
        return new EmbeddingModelCommand(new ModelEndpointCommand("http://embed", "token", "embed-model"));
    }

    private EmbeddingModelPolicy embeddingPolicy() {
        return new EmbeddingModelPolicy("http://embed", "token", "embed-model");
    }

    private KnowledgeBaseRecallCommand recallCommand(RerankGlobalRankingCommand rerankCommand) {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "collection-1",
                RetrievalMode.HYBRID,
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                List.of(),
                10,
                null,
                rerankCommand != null,
                false,
                0.0d,
                0.5d,
                0.5d,
                rerankCommand
        );
    }

    private KnowledgeBaseRecallCommand fullTextRecallCommand() {
        return new KnowledgeBaseRecallCommand(
                "kb-1",
                "collection-1",
                RetrievalMode.FULL_TEXT,
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                List.of(),
                10,
                null,
                false,
                false,
                0.0d,
                0.5d,
                0.5d,
                null
        );
    }

    private RerankGlobalRankingCommand rerankModel() {
        return new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model"));
    }
}
