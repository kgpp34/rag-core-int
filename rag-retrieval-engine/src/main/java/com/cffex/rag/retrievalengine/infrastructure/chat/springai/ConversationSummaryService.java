package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

public final class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationSummarizer summarizer;
    private final ChatProperties properties;
    private final Executor executor;
    private final Object[] conversationLocks = createConversationLocks();

    public ConversationSummaryService(
            ChatMemoryRepository chatMemoryRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummarizer summarizer,
            ChatProperties properties,
            Executor executor
    ) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.summaryRepository = summaryRepository;
        this.summarizer = summarizer;
        this.properties = properties;
        this.executor = executor;
    }

    void scheduleUpdate(String conversationId, ChatModelPolicy modelPolicy) {
        if (!properties.summaryEnabled()) {
            return;
        }
        executor.execute(() -> updateIfNeeded(conversationId, modelPolicy));
    }

    private void updateIfNeeded(String conversationId, ChatModelPolicy modelPolicy) {
        Object lock = conversationLocks[Math.floorMod(conversationId.hashCode(), conversationLocks.length)];
        synchronized (lock) {
            try {
                List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
                ConversationSummary previous = summaryRepository.findByConversationId(conversationId)
                        .orElse(new ConversationSummary(conversationId, "", 0));
                int targetMessageCount = ConversationTurnSupport.recentWindowStart(
                        history,
                        0,
                        properties.memoryWindowTurns()
                );
                int summarizedMessageCount = Math.min(previous.summarizedMessageCount(), targetMessageCount);
                int unsummarizedTurns = ConversationTurnSupport.countUserTurns(
                        history,
                        summarizedMessageCount,
                        targetMessageCount
                );
                if (unsummarizedTurns < properties.summaryTriggerTurns()) {
                    return;
                }

                List<Message> messages = history.subList(summarizedMessageCount, targetMessageCount);
                String content = summarizer.summarize(
                        modelPolicy,
                        previous.content(),
                        messages,
                        properties.summaryMaxTokens()
                );
                if (content.isBlank()) {
                    log.warn("[ChatMemory] 对话摘要为空，跳过更新, conversationId={}", conversationId);
                    return;
                }
                summaryRepository.save(new ConversationSummary(conversationId, content, targetMessageCount));
                log.info("[ChatMemory] 对话摘要已更新, conversationId={}, summarizedMessageCount={}",
                        conversationId, targetMessageCount);
            } catch (RuntimeException ex) {
                log.warn("[ChatMemory] 对话摘要更新失败，保留原始历史, conversationId={}", conversationId, ex);
            }
        }
    }

    private static Object[] createConversationLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }
}
