package com.cffex.rag.common.domain.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RetrievalContext(
        String tenantId,
        String query,
        List<String> targetKnowledgeBaseIds,
        List<String> targetDocIds,
        String embeddingModelId,
        String rerankModelId,
        int topK,
        int candidateK,
        boolean scoreThresholdEnabled,
        double scoreThreshold,
        Map<String, Object> filters,
        Map<String, Object> debugOptions
) {
    public RetrievalContext {
        tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        query = Objects.requireNonNull(query, "query must not be null");
        targetKnowledgeBaseIds = List.copyOf(Objects.requireNonNull(targetKnowledgeBaseIds, "targetKnowledgeBaseIds must not be null"));
        targetDocIds = List.copyOf(Objects.requireNonNull(targetDocIds, "targetDocIds must not be null"));
        embeddingModelId = normalize(embeddingModelId);
        rerankModelId = normalize(rerankModelId);
        filters = Map.copyOf(Objects.requireNonNull(filters, "filters must not be null"));
        debugOptions = Map.copyOf(Objects.requireNonNull(debugOptions, "debugOptions must not be null"));
    }

    public RetrievalContext(
            String tenantId,
            String query,
            List<String> targetKnowledgeBaseIds,
            List<String> targetDocIds,
            String embeddingModelId,
            String rerankModelId,
            int topK,
            int candidateK,
            Map<String, Object> filters,
            Map<String, Object> debugOptions
    ) {
        this(
                tenantId,
                query,
                targetKnowledgeBaseIds,
                targetDocIds,
                embeddingModelId,
                rerankModelId,
                topK,
                candidateK,
                false,
                0.0d,
                filters,
                debugOptions
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
