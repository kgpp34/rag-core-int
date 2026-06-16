package com.cffex.rag.retrievalengine.application.command;

import java.util.Objects;

/**
 * 模型调用端点的内部命令对象。
 */
public record ModelEndpointCommand(
        String endpoint,
        String authToken,
        String model
) {

    public ModelEndpointCommand {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }
}
