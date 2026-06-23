package com.cffex.rag.retrievalengine.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.common.service.ConversationMemoryService;
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationMemoryService.class);

    private final ChatMemoryRepository chatMemoryRepository;

    public DefaultConversationMemoryService() {
        this.chatMemoryRepository = null;
    }

    @Autowired
    public DefaultConversationMemoryService(ObjectProvider<ChatMemoryRepository> chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository.getIfAvailable();
    }

    DefaultConversationMemoryService(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

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

    @Override
    public List<String> recentUserMessages(String conversationId, int limit) {
        if (chatMemoryRepository == null || conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
            List<String> messages = new ArrayList<>(Math.min(limit, history.size()));
            for (int index = history.size() - 1; index >= 0 && messages.size() < limit; index--) {
                Message message = history.get(index);
                if (message != null && message.getMessageType() == MessageType.USER) {
                    String text = message.getText();
                    if (text != null && !text.isBlank()) {
                        messages.add(text);
                    }
                }
            }
            Collections.reverse(messages);
            return List.copyOf(messages);
        } catch (RuntimeException ex) {
            log.warn("[ChatMemory] 读取最近用户历史失败，query rewrite 将不使用历史, conversationId={}", conversationId, ex);
            return List.of();
        }
    }

}
