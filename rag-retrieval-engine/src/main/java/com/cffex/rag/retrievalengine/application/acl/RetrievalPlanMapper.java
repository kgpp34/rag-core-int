package com.cffex.rag.retrievalengine.application.acl;

import org.springframework.stereotype.Component;

import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalBinding;
import com.cffex.rag.common.domain.retrieval.RetrievalCapability;
import com.cffex.rag.retrievalengine.application.command.EmbeddingModelCommand;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.GlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.SearchBindingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;

/**
 * 上游 RetrievalPlan 到本模块内部执行命令的反腐层映射器。
 */
@Component
public class RetrievalPlanMapper {

    public ExecuteRetrievalCommand toCommand(RetrievalPlan plan) {
        return new ExecuteRetrievalCommand(
                plan.query(),
                toEmbeddingCommand(plan.embeddingSpec()),
                plan.recallSpecs().stream().map(this::toRecallCommand).toList(),
                toGlobalRankingCommand(plan.rankingSpec()),
                plan.topK(),
                plan.globalScoreThresholdEnabled(),
                plan.globalScoreThreshold()
        );
    }

    private EmbeddingModelCommand toEmbeddingCommand(EmbeddingSpec spec) {
        return new EmbeddingModelCommand(toEndpointCommand(spec.modelEndpoint()));
    }

    private KnowledgeBaseRecallCommand toRecallCommand(KnowledgeBaseRecallSpec spec) {
        return new KnowledgeBaseRecallCommand(
                spec.knowledgeBaseId(),
                spec.collectionName(),
                spec.retrievalMode(),
                toBindingCommand(spec.bindingOf(RetrievalCapability.VECTOR)),
                toBindingCommand(spec.bindingOf(RetrievalCapability.FULL_TEXT)),
                spec.docIds(),
                spec.candidateK(),
                spec.topK(),
                spec.rerankingEnabled(),
                spec.scoreThresholdEnabled(),
                spec.scoreThreshold(),
                spec.vectorWeight(),
                spec.keywordWeight(),
                spec.rerankRankingSpec() == null ? null : new RerankGlobalRankingCommand(
                        toEndpointCommand(spec.rerankRankingSpec().modelEndpoint())
                )
        );
    }

    private GlobalRankingCommand toGlobalRankingCommand(RankingSpec spec) {
        return switch (spec) {
            case RankingSpec.WeightedRankingSpec weighted ->
                    new WeightedGlobalRankingCommand(weighted.vectorWeight(), weighted.keywordWeight());
            case RankingSpec.RerankRankingSpec rerank ->
                    new RerankGlobalRankingCommand(toEndpointCommand(rerank.modelEndpoint()));
        };
    }

    private SearchBindingCommand toBindingCommand(RetrievalBinding binding) {
        return new SearchBindingCommand(binding.engineId(), binding.targetName(), binding.options());
    }

    private ModelEndpointCommand toEndpointCommand(ModelEndpointSpec spec) {
        return new ModelEndpointCommand(spec.endpoint(), spec.authToken(), spec.model());
    }
}
