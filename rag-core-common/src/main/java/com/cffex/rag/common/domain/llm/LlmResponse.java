package com.cffex.rag.common.domain.llm;

import java.util.Map;
import java.util.Objects;

/** LLM 返回结果。 */
public record LlmResponse(
        String content,
        String finishReason,
        Map<String, Object> metadata
) {
    public LlmResponse {
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
