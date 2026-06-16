package com.cffex.rag.common.domain.metadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.RetrievalCapability;

public record KnowledgeBaseMeta(
        String knowledgeBaseId,
        RetrievalMode retrievalMode,
        /** 向量存储中对应的集合名（Milvus collection / Elasticsearch index 等）。 */
        String collectionName,
        String vectorEngineId,
        String fullTextEngineId,
        List<RetrievalBindingMeta> bindings,
        boolean enabled,
        Integer topK,
        boolean rerankingEnabled,
        boolean scoreThresholdEnabled,
        double scoreThreshold,
        double vectorWeight,
        double keywordWeight,
        String name
) {
    public static final String DEFAULT_VECTOR_ENGINE_ID = "milvus";
    public static final String DEFAULT_FULL_TEXT_ENGINE_ID = "milvus";

    public KnowledgeBaseMeta {
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        retrievalMode = Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
        collectionName = Objects.requireNonNull(collectionName, "collectionName must not be null");
        vectorEngineId = Objects.requireNonNull(vectorEngineId, "vectorEngineId must not be null");
        fullTextEngineId = Objects.requireNonNull(fullTextEngineId, "fullTextEngineId must not be null");
        bindings = normalizeBindings(bindings, collectionName, vectorEngineId, fullTextEngineId);
        name = name == null || name.isBlank() ? knowledgeBaseId : name;
    }

    public KnowledgeBaseMeta(
            String knowledgeBaseId,
            RetrievalMode retrievalMode,
            String collectionName,
            boolean enabled
    ) {
        this(
                knowledgeBaseId,
                retrievalMode,
                collectionName,
                DEFAULT_VECTOR_ENGINE_ID,
                DEFAULT_FULL_TEXT_ENGINE_ID,
                null,
                enabled,
                null,
                false,
                false,
                0.0d,
                0.7d,
                0.3d,
                knowledgeBaseId
        );
    }

    public KnowledgeBaseMeta(
            String knowledgeBaseId,
            RetrievalMode retrievalMode,
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId,
            boolean enabled
    ) {
        this(
                knowledgeBaseId,
                retrievalMode,
                collectionName,
                vectorEngineId,
                fullTextEngineId,
                null,
                enabled,
                null,
                false,
                false,
                0.0d,
                0.7d,
                0.3d,
                knowledgeBaseId
        );
    }

    public KnowledgeBaseMeta(
            String knowledgeBaseId,
            RetrievalMode retrievalMode,
            String collectionName,
            boolean enabled,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                retrievalMode,
                collectionName,
                DEFAULT_VECTOR_ENGINE_ID,
                DEFAULT_FULL_TEXT_ENGINE_ID,
                null,
                enabled,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                knowledgeBaseId
        );
    }

    public KnowledgeBaseMeta(
            String knowledgeBaseId,
            RetrievalMode retrievalMode,
            String collectionName,
            List<RetrievalBindingMeta> bindings,
            boolean enabled,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {
        this(
                knowledgeBaseId,
                retrievalMode,
                collectionName,
                defaultEngineId(bindings, RetrievalCapability.VECTOR, DEFAULT_VECTOR_ENGINE_ID),
                defaultEngineId(bindings, RetrievalCapability.FULL_TEXT, DEFAULT_FULL_TEXT_ENGINE_ID),
                bindings,
                enabled,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                knowledgeBaseId
        );
    }

    public KnowledgeBaseMeta(
            String knowledgeBaseId,
            RetrievalMode retrievalMode,
            String collectionName,
            boolean enabled,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight,
            String name
    ) {
        this(
                knowledgeBaseId,
                retrievalMode,
                collectionName,
                DEFAULT_VECTOR_ENGINE_ID,
                DEFAULT_FULL_TEXT_ENGINE_ID,
                null,
                enabled,
                topK,
                rerankingEnabled,
                scoreThresholdEnabled,
                scoreThreshold,
                vectorWeight,
                keywordWeight,
                name
        );
    }

    public RetrievalBindingMeta bindingOf(RetrievalCapability capability) {
        return bindings.stream()
                .filter(RetrievalBindingMeta::enabled)
                .filter(binding -> binding.capability() == capability)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled binding for capability=%s, knowledgeBaseId=%s"
                                .formatted(capability, knowledgeBaseId)));
    }

    private static List<RetrievalBindingMeta> normalizeBindings(
            List<RetrievalBindingMeta> bindings,
            String collectionName,
            String vectorEngineId,
            String fullTextEngineId
    ) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of(
                    new RetrievalBindingMeta(
                            RetrievalCapability.VECTOR,
                            vectorEngineId,
                            collectionName,
                            Map.of(),
                            true
                    ),
                    new RetrievalBindingMeta(
                            RetrievalCapability.FULL_TEXT,
                            fullTextEngineId,
                            collectionName,
                            Map.of(),
                            true
                    )
            );
        }
        return List.copyOf(bindings);
    }

    private static String defaultEngineId(
            List<RetrievalBindingMeta> bindings,
            RetrievalCapability capability,
            String fallback
    ) {
        if (bindings == null) {
            return fallback;
        }
        return bindings.stream()
                .filter(binding -> binding.capability() == capability)
                .map(RetrievalBindingMeta::engineId)
                .findFirst()
                .orElse(fallback);
    }
}
