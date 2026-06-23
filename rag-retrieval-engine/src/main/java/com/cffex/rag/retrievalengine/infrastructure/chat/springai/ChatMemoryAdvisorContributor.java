package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

public class ChatMemoryAdvisorContributor implements ChatAdvisorContributor {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryAdvisorContributor.class);

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationSummaryService summaryService;
    private final ChatProperties properties;
    private final TokenCountEstimator tokenCountEstimator;

    public ChatMemoryAdvisorContributor(
            ChatMemoryRepository chatMemoryRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummaryService summaryService,
            ChatProperties properties
    ) {
        this(chatMemoryRepository, summaryRepository, summaryService, properties, new JTokkitTokenCountEstimator());
    }

    ChatMemoryAdvisorContributor(
            ChatMemoryRepository chatMemoryRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummaryService summaryService,
            ChatProperties properties,
            TokenCountEstimator tokenCountEstimator
    ) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.summaryRepository = summaryRepository;
        this.summaryService = summaryService;
        this.properties = properties;
        this.tokenCountEstimator = tokenCountEstimator;
    }

    @Override
    public List<Advisor> advisors(ChatCompletionRequest request) {
        log.info("[ChatMemory] advisors() 被调用, memoryEnabled={}, userId={}, conversationId={}",
                properties.memoryEnabled(),
                request.userId(),
                request.conversationId());

        if (!properties.memoryEnabled()) {
            log.info("[ChatMemory] memoryEnabled=false, 跳过记忆 Advisor");
            return List.of();
        }
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            log.info("[ChatMemory] conversationId 为空, 跳过记忆 Advisor");
            return List.of();
        }

        try {
            String effectiveConversationId = request.conversationId();
            int memoryTokenBudget = resolveMemoryTokenBudget(request);
            if (memoryTokenBudget == 0) {
                log.warn("[ChatMemory] 历史上下文 token 预算为 0，将不注入历史消息。"
                                + " maxRequestContextTokens={}, memoryTokenBudgetRatio={}, outputTokens={}, requestTokens={}",
                        properties.maxRequestContextTokens(),
                        properties.memoryTokenBudgetRatio(),
                        request.maxTokens(),
                        estimateRequestTokens(request));
            }
            ChatMemory memory = new TokenBudgetChatMemory(
                    chatMemoryRepository,
                    summaryRepository,
                    summaryService,
                    request.modelPolicy(),
                    tokenCountEstimator,
                    memoryTokenBudget,
                    properties.memoryWindowTurns()
            );

            log.info("[ChatMemory] 创建 token 预算 Advisor, effectiveConversationId={}, memoryTokenBudget={}",
                    effectiveConversationId, memoryTokenBudget);

            return List.of(ReliableMessageChatMemoryAdvisor.builder(memory)
                    .conversationId(effectiveConversationId)
                    .build());
        } catch (Exception ex) {
            log.warn("[ChatMemory] 对话记忆 Advisor 创建失败，降级为无记忆模式，userId={}", request.userId(), ex);
            return List.of();
        }
    }
    int resolveMemoryTokenBudget(ChatCompletionRequest request) {
        int requestTokens = estimateRequestTokens(request);
        int outputTokens = request.maxTokens() == null ? 0 : request.maxTokens();
        int maxRequestContextTokens = Math.max(0, properties.maxRequestContextTokens());
        int availableTokens = Math.max(0, maxRequestContextTokens - outputTokens - requestTokens);
        int ratioBudget = (int) Math.floor(maxRequestContextTokens * normalizedMemoryTokenBudgetRatio());
        return Math.max(0, Math.min(availableTokens, ratioBudget));
    }

    private double normalizedMemoryTokenBudgetRatio() {
        double ratio = properties.memoryTokenBudgetRatio();
        if (Double.isNaN(ratio) || ratio <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, ratio);
    }

    private int estimateRequestTokens(ChatCompletionRequest request) {
        return request.messages().stream()
                .mapToInt(message -> MESSAGE_OVERHEAD_TOKENS + tokenCountEstimator.estimate(message.content()))
                .sum();
    }

}
