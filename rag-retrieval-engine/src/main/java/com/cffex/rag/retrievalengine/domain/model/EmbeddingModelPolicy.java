package com.cffex.rag.retrievalengine.domain.model;

import java.util.Objects;

/**
 * 向量化模型策略。
 */
public record EmbeddingModelPolicy(
        String endpoint,
        String authToken,
        String model
) {

    public EmbeddingModelPolicy {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }
}
