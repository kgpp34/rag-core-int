package com.cffex.rag.common.domain.llm;

import java.util.Objects;

/** 单条 LLM 消息。 */
public record LlmMessage(
        LlmMessageRole role,
        String content
) {
    public LlmMessage {
        role = Objects.requireNonNull(role, "role must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
