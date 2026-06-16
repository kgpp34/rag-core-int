package com.cffex.rag.retrievalengine.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 聊天补全请求。
 */
public record ChatCompletionRequest(
        ChatModelPolicy modelPolicy,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        String userId,
        String conversationId
) {

    public ChatCompletionRequest {
        Objects.requireNonNull(modelPolicy, "modelPolicy must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
    }
}
