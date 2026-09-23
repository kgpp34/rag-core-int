package com.cffex.rag.common.domain.memory;

/** A transport-neutral conversation message used across application boundaries. */
public record ConversationMessage(
        String role,
        String content
) {
}
