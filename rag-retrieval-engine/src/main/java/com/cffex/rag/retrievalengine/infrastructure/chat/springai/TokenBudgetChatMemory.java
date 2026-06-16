package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

/**
 * Persists complete conversation history while exposing only the recent messages
 * that fit into the token budget for the current model request.
 */
final class TokenBudgetChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetChatMemory.class);

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final ChatMemoryRepository repository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationSummaryService summaryService;
    private final ChatModelPolicy modelPolicy;
    private final TokenCountEstimator tokenCountEstimator;
    private final int maxTokens;

    TokenBudgetChatMemory(
            ChatMemoryRepository repository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummaryService summaryService,
            ChatModelPolicy modelPolicy,
            TokenCountEstimator tokenCountEstimator,
            int maxTokens
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.summaryRepository = Objects.requireNonNull(summaryRepository);
        this.summaryService = Objects.requireNonNull(summaryService);
        this.modelPolicy = Objects.requireNonNull(modelPolicy);
        this.tokenCountEstimator = Objects.requireNonNull(tokenCountEstimator);
        this.maxTokens = Math.max(0, maxTokens);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Message> history = new ArrayList<>(repository.findByConversationId(conversationId));
        messages.stream().filter(Objects::nonNull).forEach(history::add);
        repository.saveAll(conversationId, history);
        if (messages.stream().anyMatch(message -> message != null
                && message.getMessageType() == MessageType.ASSISTANT)) {
            summaryService.scheduleUpdate(conversationId, modelPolicy);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> history = repository.findByConversationId(conversationId);
        if (maxTokens == 0 || history.isEmpty()) {
            log.info("[ChatMemory] 历史消息未注入, conversationId={}, historyCount={}, memoryTokenBudget={}",
                    conversationId, history.size(), maxTokens);
            return List.of();
        }

        ConversationSummary summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        List<Message> selected = new ArrayList<>();
        int usedTokens = 0;
        int lowerBound = 0;
        if (summary != null && summary.content() != null && !summary.content().isBlank()) {
            Message summaryMessage = new SystemMessage("以下是较早对话的摘要：\n" + summary.content());
            int summaryTokens = estimate(summaryMessage);
            if (summaryTokens <= maxTokens) {
                selected.add(summaryMessage);
                usedTokens = summaryTokens;
                lowerBound = Math.min(summary.summarizedMessageCount(), history.size());
            }
        }

        List<Message> recentMessages = new ArrayList<>();
        for (int index = history.size() - 1; index >= lowerBound; index--) {
            Message message = history.get(index);
            int messageTokens = estimate(message);
            if (usedTokens + messageTokens > maxTokens) {
                break;
            }
            recentMessages.add(0, message);
            usedTokens += messageTokens;
        }
        selected.addAll(recentMessages);
        log.info("[ChatMemory] 历史消息已装配, conversationId={}, historyCount={}, selectedCount={}, "
                        + "recentCount={}, hasSummary={}, memoryTokenBudget={}, usedTokens={}",
                conversationId,
                history.size(),
                selected.size(),
                recentMessages.size(),
                summary != null && summary.content() != null && !summary.content().isBlank(),
                maxTokens,
                usedTokens);
        return List.copyOf(selected);
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
        summaryRepository.deleteByConversationId(conversationId);
    }

    private int estimate(Message message) {
        String text = message.getText();
        return MESSAGE_OVERHEAD_TOKENS + (text == null || text.isBlank() ? 0 : tokenCountEstimator.estimate(text));
    }
}
