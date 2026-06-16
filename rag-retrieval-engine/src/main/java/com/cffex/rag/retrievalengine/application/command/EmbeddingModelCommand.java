package com.cffex.rag.retrievalengine.application.command;

import java.util.Objects;

/**
 * embedding 模型内部命令对象。
 */
public record EmbeddingModelCommand(ModelEndpointCommand modelEndpoint) {

    public EmbeddingModelCommand {
        Objects.requireNonNull(modelEndpoint, "modelEndpoint must not be null");
    }
}
