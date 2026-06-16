package com.cffex.rag.retrievalengine.domain.model;

import java.util.Objects;

/**
 * 聊天模型策略。
 */
public record ChatModelPolicy(
        String endpoint,
        String authToken,
        String model
) {

    public ChatModelPolicy {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }
}
