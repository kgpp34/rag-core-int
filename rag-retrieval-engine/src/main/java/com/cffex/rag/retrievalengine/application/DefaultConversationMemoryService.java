package com.cffex.rag.retrievalengine.application;

import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.common.service.ConversationMemoryService;
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    @Override
    public ConversationMemoryContext resolve(ConversationMemoryRequest request) {
        if (!request.enabled()) {
            return new ConversationMemoryContext(request.userId(), null);
        }
        String conversationId = resolveConversationId(request);
        return new ConversationMemoryContext(request.userId(), conversationId);
    }

    private String resolveConversationId(ConversationMemoryRequest request) {
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_REQUEST,
                    "启用对话记忆时 conversationId 不能为空，请由客户端生成会话标识"
            );
        }
        return conversationId;
    }

}
