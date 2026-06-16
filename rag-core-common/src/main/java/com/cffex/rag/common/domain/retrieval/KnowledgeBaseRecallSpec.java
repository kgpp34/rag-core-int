package com.cffex.rag.common.domain.retrieval;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.RetrievalMode;

/**
 * 单个知识库的已解析召回规格。
 *
 * <p>由 query-planner 根据 KnowledgeBaseMeta 展开，包含执行召回所需的全部参数，
 * HybridRecallCoordinator 依据此对象直接构造 StorageSearchRequest，无需再查元数据。
 */
public record KnowledgeBaseRecallSpec(
        String knowledgeBaseId,
        String collectionName,
        RetrievalMode retrievalMode,
        List<RetrievalBinding> bindings,
        List<String> docIds,
        int candidateK,
        Integer topK,
        boolean rerankingEnabled,
        boolean scoreThresholdEnabled,
        double scoreThreshold,
        double vectorWeight,
        double keywordWeight,
        RankingSpec.RerankRankingSpec rerankRankingSpec
) {
    public KnowledgeBaseRecallSpec {
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        collectionName = Objects.requireNonNull(collectionName, "collectionName must not be null");
        retrievalMode = Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
        bindings = normalizeBindings(bindings, collectionName);
        docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds must not be null"));
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(
                        collectionName,
                        KnowledgeBaseMeta.DEFAULT_VECTOR_ENGINE_ID,
                        KnowledgeBaseMeta.DEFAULT_FULL_TEXT_ENGINE_ID
                ),
                docIds,
                candidateK,
                null,
                false,
                false,
                0.0d,
                0.7d,
                0.3d,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(collectionName, vectorEngineId, fullTextEngineId),
                docIds,
                candidateK,
                null,
                false,
                false,
                0.0d,
                0.7d,
                0.3d,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(
                        collectionName,
                        KnowledgeBaseMeta.DEFAULT_VECTOR_ENGINE_ID,
                        KnowledgeBaseMeta.DEFAULT_FULL_TEXT_ENGINE_ID
                ),
                docIds,
                candidateK,
                null,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(collectionName, vectorEngineId, fullTextEngineId),
                docIds,
                candidateK,
                null,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(
                        collectionName,
                        KnowledgeBaseMeta.DEFAULT_VECTOR_ENGINE_ID,
                        KnowledgeBaseMeta.DEFAULT_FULL_TEXT_ENGINE_ID
                ),
                docIds,
                candidateK,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(collectionName, vectorEngineId, fullTextEngineId),
                docIds,
                candidateK,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                null
        );
    }

    public KnowledgeBaseRecallSpec(
            String knowledgeBaseId,
            String collectionName,
            RetrievalMode retrievalMode,
            List<String> docIds,
            int candidateK,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight,
            RankingSpec.RerankRankingSpec rerankRankingSpec
    ) {
        this(
                knowledgeBaseId,
                collectionName,
                retrievalMode,
                defaultBindings(
                        collectionName,
                        KnowledgeBaseMeta.DEFAULT_VECTOR_ENGINE_ID,
                        KnowledgeBaseMeta.DEFAULT_FULL_TEXT_ENGINE_ID
                ),
                docIds,
                candidateK,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                rerankRankingSpec
        );
    }

    public boolean hasRerankRankingSpec() {
        return rerankRankingSpec != null;
    }

    public RetrievalBinding bindingOf(RetrievalCapability capability) {
        return bindings.stream()
                .filter(binding -> binding.capability() == capability)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing binding for capability=%s, knowledgeBaseId=%s"
                                .formatted(capability, knowledgeBaseId)));
    }

    public String vectorEngineId() {
        return bindingOf(RetrievalCapability.VECTOR).engineId();
    }

    public String fullTextEngineId() {
        return bindingOf(RetrievalCapability.FULL_TEXT).engineId();
    }

    private static List<RetrievalBinding> normalizeBindings(List<RetrievalBinding> bindings, String collectionName) {
        if (bindings == null || bindings.isEmpty()) {
            return defaultBindings(
                    collectionName,
                    KnowledgeBaseMeta.DEFAULT_VECTOR_ENGINE_ID,
                    KnowledgeBaseMeta.DEFAULT_FULL_TEXT_ENGINE_ID
            );
        }
        return List.copyOf(bindings);
    }

    private static List<RetrievalBinding> defaultBindings(
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId
    ) {
        return List.of(
                new RetrievalBinding(RetrievalCapability.VECTOR, vectorEngineId, collectionName),
                new RetrievalBinding(RetrievalCapability.FULL_TEXT, fullTextEngineId, collectionName)
        );
    }
}
