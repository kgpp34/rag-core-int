package com.cffex.rag.retrievalengine.application.query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.application.command.EmbeddingModelCommand;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;

@ExtendWith(MockitoExtension.class)
class DefaultQueryPreprocessorTest {

    @Mock
    private QueryTransformer queryTransformer;

    @Test
    void preprocess_appliesTransformerAndReturnsInternalProcessedQuery() {
        ExecuteRetrievalCommand command = command();
        Query transformed = new Query("rewritten", List.of(), Map.of("custom", "value"));
        DefaultQueryPreprocessor preprocessor = new DefaultQueryPreprocessor(List.of(queryTransformer));

        when(queryTransformer.transform(new Query("hello", List.of(), Map.of(
                DefaultQueryPreprocessor.RETRIEVAL_COMMAND_CONTEXT_KEY, command
        )))).thenReturn(transformed);

        ProcessedQuery query = preprocessor.preprocess(new RetrievalExecutionContext(command));

        assertThat(query.text()).isEqualTo("rewritten");
    }

    private ExecuteRetrievalCommand command() {
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
                new WeightedGlobalRankingCommand(0.5d, 0.5d),
                5,
                false,
                0.0d
        );
    }
}
