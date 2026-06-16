package com.cffex.rag.retrievalengine.domain.model;

import java.util.Objects;

/**
 * 聊天消息。
 */
public record ChatMessage(
        ChatMessageRole role,
        String content
) {

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
