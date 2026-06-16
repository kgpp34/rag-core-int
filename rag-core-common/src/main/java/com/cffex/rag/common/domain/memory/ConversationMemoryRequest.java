package com.cffex.rag.common.domain.memory;

/** 上层发往会话记忆服务的请求。 */
public record ConversationMemoryRequest(
        String userId,
        boolean enabled,
        String conversationId
) {
}
