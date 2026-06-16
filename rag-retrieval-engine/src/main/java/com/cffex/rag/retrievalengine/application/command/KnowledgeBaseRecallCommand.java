package com.cffex.rag.retrievalengine.application.command;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;

/**
 * 单知识库召回内部命令对象。
 */
public record KnowledgeBaseRecallCommand(
        String knowledgeBaseId,
        String collectionName,
        RetrievalMode retrievalMode,
        SearchBindingCommand vectorBinding,
        SearchBindingCommand fullTextBinding,
        List<String> docIds,
        int candidateK,
        Integer topK,
        boolean rerankingEnabled,
        boolean scoreThresholdEnabled,
        double scoreThreshold,
        double vectorWeight,
        double keywordWeight,
        RerankGlobalRankingCommand rerankCommand
) {

    public KnowledgeBaseRecallCommand {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(collectionName, "collectionName must not be null");
        Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
        Objects.requireNonNull(vectorBinding, "vectorBinding must not be null");
        Objects.requireNonNull(fullTextBinding, "fullTextBinding must not be null");
        docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds must not be null"));
    }

    public boolean hasRerankCommand() {
        return rerankCommand != null;
    }

    public KnowledgeBaseRecallPolicy recallPolicy() {
        return new KnowledgeBaseRecallPolicy(
                retrievalMode,
                candidateK,
                topK,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                rerankCommand == null ? null : new RerankModelPolicy(
                        rerankCommand.modelEndpoint().endpoint(),
                        rerankCommand.modelEndpoint().authToken(),
                        rerankCommand.modelEndpoint().model()
                )
        );
    }
}
