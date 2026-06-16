package com.cffex.rag.common.domain.metadata;

import java.util.Objects;

public record ModelMeta(
        String modelId,
        String modelName,
        ModelType modelType,
        String baseUrl,
        String apiKey,
        boolean enabled
) {
    public ModelMeta {
        modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        modelType = Objects.requireNonNull(modelType, "modelType must not be null");
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    }
}
