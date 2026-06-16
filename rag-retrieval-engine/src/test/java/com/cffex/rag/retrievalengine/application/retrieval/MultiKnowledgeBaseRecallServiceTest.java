package com.cffex.rag.retrievalengine.application.retrieval;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.application.command.EmbeddingModelCommand;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;
import com.cffex.rag.retrievalengine.application.port.ParallelExecutionStrategy;
import com.cffex.rag.retrievalengine.application.query.ProcessedQuery;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

@ExtendWith(MockitoExtension.class)
class MultiKnowledgeBaseRecallServiceTest {

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
    private KnowledgeBaseRetrievalTask knowledgeBaseRetrievalTask;

    @Test
    void recall_passesPrecomputedQueryVectorToEachKnowledgeBaseTask() {
        MultiKnowledgeBaseRecallService service = new MultiKnowledgeBaseRecallService(SYNC, knowledgeBaseRetrievalTask);
        KnowledgeBaseRecallCommand kb1 = recallCommand("kb-1");
        KnowledgeBaseRecallCommand kb2 = recallCommand("kb-2");
        RetrievalExecutionContext context = new RetrievalExecutionContext(command(kb1, kb2))
                .withQueryVector(new float[]{0.1f, 0.2f});
        ProcessedQuery query = new ProcessedQuery("rewritten hello");

        when(knowledgeBaseRetrievalTask.execute(eq("rewritten hello"), any(float[].class), eq(kb1), any()))
                .thenReturn(List.of(candidate("chunk-1", "kb-1")));
        when(knowledgeBaseRetrievalTask.execute(eq("rewritten hello"), any(float[].class), eq(kb2), any()))
                .thenReturn(List.of(candidate("chunk-2", "kb-2")));

        List<RetrievalCandidate> candidates = service.recall(query, context);
        ArgumentCaptor<float[]> queryVectorCaptor = ArgumentCaptor.forClass(float[].class);

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactlyInAnyOrder("chunk-1", "chunk-2");
        verify(knowledgeBaseRetrievalTask, times(2))
                .execute(eq("rewritten hello"), queryVectorCaptor.capture(), any(KnowledgeBaseRecallCommand.class), any());
        assertThat(queryVectorCaptor.getAllValues()).hasSize(2);
        assertThat(queryVectorCaptor.getAllValues().get(0)).containsExactly(0.1f, 0.2f);
        assertThat(queryVectorCaptor.getAllValues().get(1)).containsExactly(0.1f, 0.2f);
    }

    private ExecuteRetrievalCommand command(KnowledgeBaseRecallCommand... recallCommands) {
        return new ExecuteRetrievalCommand(
                "hello",
                new EmbeddingModelCommand(new ModelEndpointCommand("http://embed", "token", "embed-model")),
                List.of(recallCommands),
                new WeightedGlobalRankingCommand(0.5d, 0.5d),
                5,
                false,
                0.0d
        );
    }

    private KnowledgeBaseRecallCommand recallCommand(String knowledgeBaseId) {
        return new KnowledgeBaseRecallCommand(
                knowledgeBaseId,
                knowledgeBaseId + "-collection",
                RetrievalMode.HYBRID,
                new SearchBindingCommand("milvus", knowledgeBaseId + "-collection", Map.of()),
                new SearchBindingCommand("milvus", knowledgeBaseId + "-collection", Map.of()),
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

    private RetrievalCandidate candidate(String chunkId, String knowledgeBaseId) {
        return new RetrievalCandidate(chunkId, "doc-1", knowledgeBaseId, "content", 0.9d, 0.2d, Map.of());
    }
}
