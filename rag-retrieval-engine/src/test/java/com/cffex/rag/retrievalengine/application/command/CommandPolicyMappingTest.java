package com.cffex.rag.retrievalengine.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.domain.model.GlobalRerankPolicy;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;
import com.cffex.rag.retrievalengine.domain.model.WeightedRankingPolicy;

class CommandPolicyMappingTest {

    @Test
    void knowledgeBaseRecallCommand_mapsToRecallPolicy() {
        KnowledgeBaseRecallCommand command = new KnowledgeBaseRecallCommand(
                "kb-1",
                "collection-1",
                RetrievalMode.HYBRID,
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                new SearchBindingCommand("milvus", "collection-1", Map.of()),
                List.of(),
                10,
                5,
                true,
                true,
                0.7d,
                0.6d,
                0.4d,
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "model"))
        );

        KnowledgeBaseRecallPolicy policy = command.recallPolicy();

        assertThat(policy.usesDenseRoute()).isTrue();
        assertThat(policy.usesSparseRoute()).isTrue();
        assertThat(policy.resolvedTopK()).isEqualTo(5);
        assertThat(policy.routeCandidateK()).isEqualTo(10);
        assertThat(policy.resolvedVectorWeight()).isEqualTo(0.6d);
        assertThat(policy.resolvedKeywordWeight()).isEqualTo(0.4d);
        assertThat(policy.hasRerankPolicy()).isTrue();
    }

    @Test
    void executeRetrievalCommand_mapsWeightedRankingToPolicy() {
        ExecuteRetrievalCommand command = new ExecuteRetrievalCommand(
                "hello",
                new EmbeddingModelCommand(new ModelEndpointCommand("http://embed", "token", "embed-model")),
                List.of(),
                new WeightedGlobalRankingCommand(0.2d, 0.8d),
                6,
                false,
                0.0d
        );

        assertThat(command.globalRankingPolicy())
                .isInstanceOf(WeightedRankingPolicy.class)
                .extracting("topK", "vectorWeight", "keywordWeight")
                .containsExactly(6, 0.2d, 0.8d);
    }

    @Test
    void executeRetrievalCommand_mapsRerankToPolicy() {
        ExecuteRetrievalCommand command = new ExecuteRetrievalCommand(
                "hello",
                new EmbeddingModelCommand(new ModelEndpointCommand("http://embed", "token", "embed-model")),
                List.of(),
                new RerankGlobalRankingCommand(new ModelEndpointCommand("http://rerank", "token", "rerank-model")),
                4,
                true,
                0.9d
        );

        assertThat(command.globalRankingPolicy())
                .isInstanceOf(GlobalRerankPolicy.class)
                .extracting("topK", "scoreThresholdEnabled", "scoreThreshold")
                .containsExactly(4, true, 0.9d);
    }
}
