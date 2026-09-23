package com.cffex.rag.retrievalengine.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMessage;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.common.service.ConversationMemoryService;
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationMemoryService.class);

    private final ChatMemoryRepository chatMemoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DefaultConversationMemoryService() {
        this.chatMemoryRepository = null;
        this.jdbcTemplate = null;
        this.transactionTemplate = null;
    }

    @Autowired
    public DefaultConversationMemoryService(
            ObjectProvider<ChatMemoryRepository> chatMemoryRepository,
            @Qualifier("jdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplate,
            @Qualifier("transactionManager") ObjectProvider<PlatformTransactionManager> transactionManager
    ) {
        this.chatMemoryRepository = chatMemoryRepository.getIfAvailable();
        this.jdbcTemplate = jdbcTemplate.getIfAvailable();
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        this.transactionTemplate = manager == null ? null : new TransactionTemplate(manager);
    }

    DefaultConversationMemoryService(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.jdbcTemplate = null;
        this.transactionTemplate = null;
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
                    String text = normalizeUserMessageForRewrite(message.getText());
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

    @Override
    public List<ConversationMessage> recentMessages(String conversationId, int limit) {
        if (chatMemoryRepository == null || conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        int start = Math.max(0, history.size() - limit);
        List<ConversationMessage> messages = new ArrayList<>(history.size() - start);
        for (int index = start; index < history.size(); index++) {
            Message message = history.get(index);
            if (message == null || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            if (message.getMessageType() == MessageType.USER) {
                messages.add(new ConversationMessage("user", normalizeUserMessageForRewrite(message.getText())));
            } else if (message.getMessageType() == MessageType.ASSISTANT) {
                messages.add(new ConversationMessage("assistant", message.getText()));
            }
        }
        return List.copyOf(messages);
    }

    @Override
    public void appendExchange(String conversationId, String userMessage, String assistantMessage) {
        if (chatMemoryRepository == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<Message> history = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(assistantMessage));
        chatMemoryRepository.saveAll(conversationId, history);
    }

    @Override
    public boolean appendExchangeOnce(
            String exchangeId,
            String conversationId,
            String userMessage,
            String assistantMessage
    ) {
        if (jdbcTemplate == null || transactionTemplate == null) {
            appendExchange(conversationId, userMessage, assistantMessage);
            return true;
        }
        Boolean appended = transactionTemplate.execute(status -> {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO t_rag_memory_exchange (exchange_id, conversation_id)
                    VALUES (?, ?)
                    ON CONFLICT (exchange_id) DO NOTHING
                    """, exchangeId, conversationId);
            if (inserted == 0) {
                return false;
            }
            appendExchange(conversationId, userMessage, assistantMessage);
            return true;
        });
        return Boolean.TRUE.equals(appended);
    }

    private static String normalizeUserMessageForRewrite(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        String questionMarker = "用户问题：";
        int questionStart = normalized.indexOf(questionMarker);
        if (questionStart < 0) {
            return normalized;
        }
        int valueStart = questionStart + questionMarker.length();
        int chunksStart = normalized.indexOf("知识片段：", valueStart);
        String question = chunksStart >= 0
                ? normalized.substring(valueStart, chunksStart)
                : normalized.substring(valueStart);
        question = question.trim();
        return question.isBlank() ? normalized : question;
    }

}
