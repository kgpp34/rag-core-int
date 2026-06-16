package com.cffex.rag.common.domain.memory;

/** LLM 请求使用的会话记忆上下文。 */
public record ConversationMemoryContext(
        String userId,
        String conversationId
) {
}
