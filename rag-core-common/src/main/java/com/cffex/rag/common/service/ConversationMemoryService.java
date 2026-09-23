package com.cffex.rag.common.service;

import java.util.List;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMessage;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;

/** 解析会话标识，供上层构造 LLM 请求。 */
public interface ConversationMemoryService {

    ConversationMemoryContext resolve(ConversationMemoryRequest request);

    default List<String> recentUserMessages(String conversationId, int limit) {
        return List.of();
    }

    default List<ConversationMessage> recentMessages(String conversationId, int limit) {
        return List.of();
    }

    default void appendExchange(String conversationId, String userMessage, String assistantMessage) {
    }

    /** Appends one exchange once for a durable caller-provided idempotency key. */
    default boolean appendExchangeOnce(
            String exchangeId,
            String conversationId,
            String userMessage,
            String assistantMessage
    ) {
        appendExchange(conversationId, userMessage, assistantMessage);
        return true;
    }
}
