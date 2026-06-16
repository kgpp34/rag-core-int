package com.cffex.rag.retrievalengine.domain.model;

import java.util.Objects;

/**
 * 重排模型策略。
 */
public record RerankModelPolicy(
        String endpoint,
        String authToken,
        String model
) {

    public RerankModelPolicy {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }
}
