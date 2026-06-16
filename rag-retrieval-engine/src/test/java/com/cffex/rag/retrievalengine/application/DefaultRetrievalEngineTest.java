package com.cffex.rag.retrievalengine.application;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalResult;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.common.service.RagProcessEventPublisher.RagProcessStage;
import com.cffex.rag.common.service.RagProcessEventPublisher.StageHandle;
import com.cffex.rag.retrievalengine.application.acl.RetrievalPlanMapper;
import com.cffex.rag.retrievalengine.application.command.EmbeddingModelCommand;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.debug.DebugTraceContext;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionResult;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionService;

@ExtendWith(MockitoExtension.class)
class DefaultRetrievalEngineTest {

    @Mock
    private RetrievalExecutionService retrievalExecutionService;

    @Mock
    private RerankRankingService rerankRankingService;

    @Mock
    private RetrievalPlanMapper retrievalPlanMapper;

    @Mock
    private DebugTraceContext debugTraceContext;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    @Test
    void execute_delegatesToExecutionServiceAndWrapsExecutionResult() {
        DefaultRetrievalEngine engine = engine();
        RetrievalPlan plan = plan(RankingSpec.WeightedRankingSpec.equalWeight());
        ExecuteRetrievalCommand command = command(plan);
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.91, 0.44, 0.81, "chunk content",
                Map.of("source", "spring-ai", "document_name", "Document A", "upload_file_id", "file-123"));
        RetrievalExecutionResult executionResult = new RetrievalExecutionResult(
                List.of(rankedChunk),
                1,
                "WeightedRankingSpec",
                "weighted_fallback",
                false,
                false,
                0.0d,
                true,
                0,
                1
        );

        when(retrievalPlanMapper.toCommand(plan)).thenReturn(command);
        when(retrievalExecutionService.execute(any(RetrievalExecutionContext.class))).thenReturn(executionResult);

        RetrievalResult result = engine.execute(plan);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        assertThat(result.debugTrace())
                .containsEntry("dense_retrieval_provider", "spring_ai_rag")
                .containsEntry("ranking_mode", "weighted_fallback")
                .containsEntry("weighted_fallback", true)
                .containsEntry("candidate_count", 1);
        assertThat(result.requestId()).isNotBlank();
        verify(retrievalPlanMapper).toCommand(plan);
        verify(retrievalExecutionService).execute(any(RetrievalExecutionContext.class));
    }

    @Test
    void execute_preservesExecutionDebugTraceForGlobalRerank() {
        RankingSpec.RerankRankingSpec rerankSpec = new RankingSpec.RerankRankingSpec(
                new ModelEndpointSpec("http://rerank", "token", "rerank-model")
        );
        RetrievalPlan plan = plan(rerankSpec);
        ExecuteRetrievalCommand command = command(plan);
        DefaultRetrievalEngine engine = engine();
        RetrievedChunk rankedChunk = new RetrievedChunk(
                "chunk-1", "doc-1", "kb-1", 0.91, 0.44, 0.96, "chunk content",
                Map.of("source", "spring-ai", "document_name", "Document A", "upload_file_id", "file-123"));
        RetrievalExecutionResult executionResult = new RetrievalExecutionResult(
                List.of(rankedChunk),
                1,
                "RerankRankingSpec",
                "global_rerank",
                true,
                false,
                0.0d,
                false,
                1,
                1
        );

        when(retrievalPlanMapper.toCommand(plan)).thenReturn(command);
        when(retrievalExecutionService.execute(any(RetrievalExecutionContext.class))).thenReturn(executionResult);

        RetrievalResult result = engine.execute(plan);

        assertThat(result.chunks()).containsExactly(rankedChunk);
        assertThat(result.debugTrace())
                .containsEntry("dense_retrieval_provider", "spring_ai_rag")
                .containsEntry("ranking_mode", "global_rerank")
                .containsEntry("global_rerank_enabled", true)
                .containsEntry("weighted_fallback", false);
    }

    @Test
    void rerank_publishesFinalKnowledgeBasesForRewriteMergeScope() {
        DefaultRetrievalEngine engine = engine();
        ModelEndpointSpec rerankModel = new ModelEndpointSpec("http://rerank", "token", "rerank-model");
        List<RetrievedChunk> input = List.of(
                chunk("chunk-1", "kb-planned-only", 0.91),
                chunk("chunk-2", "kb-final-2", 0.82),
                chunk("chunk-3", "kb-final-1", 0.73)
        );
        List<RetrievedChunk> reranked = List.of(
                chunk("chunk-3", "kb-final-1", "最终知识库一", 0.99),
                chunk("chunk-2", "kb-final-2", "最终知识库二", 0.88),
                chunk("chunk-4", "kb-final-1", "最终知识库一", 0.77)
        );
        RecordingPublisher publisher = new RecordingPublisher();
        when(rerankRankingService.rank(any(), any(), any(), anyInt())).thenReturn(reranked);

        List<RetrievedChunk> result = engine.rerank("hello", input, rerankModel, 3, publisher, "rewrite_merge");

        assertThat(result).containsExactlyElementsOf(reranked);
        assertThat(publisher.completedDetails)
                .containsEntry("scope", "rewrite_merge")
                .containsEntry("inputCount", 3)
                .containsEntry("outputCount", 3)
                .containsEntry("knowledgeBaseCount", 2)
                .containsEntry("knowledgeBases", List.of(
                        Map.of("knowledgeBaseId", "kb-final-1", "name", "最终知识库一"),
                        Map.of("knowledgeBaseId", "kb-final-2", "name", "最终知识库二")
                ));
    }

    private DefaultRetrievalEngine engine() {
        return new DefaultRetrievalEngine(
                retrievalPlanMapper,
                retrievalExecutionService,
                rerankRankingService,
                debugTraceContext,
                debugTraceWriter
        );
    }

    private RetrievalPlan plan(RankingSpec rankingSpec) {
        return new RetrievalPlan(
                "hello",
                new EmbeddingSpec(new ModelEndpointSpec("http://embed", "token", "embed-model")),
                List.of(new KnowledgeBaseRecallSpec("kb-1", "collection-1", RetrievalMode.HYBRID, List.of(), 10)),
                rankingSpec,
                5
        );
    }

    private ExecuteRetrievalCommand command(RetrievalPlan ignored) {
        return new ExecuteRetrievalCommand(
                "hello",
                new EmbeddingModelCommand(new ModelEndpointCommand("http://embed", "token", "embed-model")),
                List.of(new KnowledgeBaseRecallCommand(
                        "kb-1",
                        "collection-1",
                        RetrievalMode.HYBRID,
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
                )),
                ignored.rankingSpec() instanceof RankingSpec.WeightedRankingSpec
                        ? new WeightedGlobalRankingCommand(0.5d, 0.5d)
                        : new RerankGlobalRankingCommand(
                                new ModelEndpointCommand("http://rerank", "token", "rerank-model")
                        ),
                5,
                false,
                0.0d
        );
    }

    private static RetrievedChunk chunk(String chunkId, String knowledgeBaseId, double rankingScore) {
        return new RetrievedChunk(
                chunkId,
                "doc-" + chunkId,
                knowledgeBaseId,
                rankingScore,
                null,
                rankingScore,
                "content " + chunkId,
                Map.of()
        );
    }

    private static RetrievedChunk chunk(
            String chunkId,
            String knowledgeBaseId,
            String knowledgeBaseName,
            double rankingScore
    ) {
        return new RetrievedChunk(
                chunkId,
                "doc-" + chunkId,
                knowledgeBaseId,
                rankingScore,
                null,
                rankingScore,
                "content " + chunkId,
                Map.of("knowledgeBaseName", knowledgeBaseName)
        );
    }

    private static final class RecordingPublisher implements RagProcessEventPublisher {

        private Map<String, Object> completedDetails = Map.of();

        @Override
        public StageHandle start(RagProcessStage stage, Map<String, Object> details) {
            return new StageHandle("test-rerank", stage, System.nanoTime());
        }

        @Override
        public void complete(StageHandle handle, Map<String, Object> details) {
            completedDetails = details;
        }
    }
}
