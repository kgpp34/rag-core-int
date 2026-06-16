package com.cffex.rag.retrievalengine.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 流式聊天补全分片。
 */
public record ChatCompletionChunk(
        String delta,
        String finishReason,
        boolean completed,
        Map<String, Object> metadata
) {

    public ChatCompletionChunk {
        Objects.requireNonNull(delta, "delta must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
