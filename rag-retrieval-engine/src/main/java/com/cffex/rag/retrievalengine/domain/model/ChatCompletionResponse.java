package com.cffex.rag.retrievalengine.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 聊天补全响应。
 */
public record ChatCompletionResponse(
        String content,
        String finishReason,
        Map<String, Object> metadata
) {

    public ChatCompletionResponse {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
