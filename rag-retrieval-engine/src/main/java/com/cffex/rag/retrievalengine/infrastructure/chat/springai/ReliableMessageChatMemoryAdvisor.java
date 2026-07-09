package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class ReliableMessageChatMemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessageChatMemoryAdvisor.class);

    private final ChatMemory chatMemory;

    private final String defaultConversationId;

    private final int order;

    private final Scheduler scheduler;

    private final LlmMessageTraceSupport traceSupport;

    private final String traceId;

    public ReliableMessageChatMemoryAdvisor(ChatMemory chatMemory, String defaultConversationId, int order,
            Scheduler scheduler) {
        this(chatMemory, defaultConversationId, order, scheduler, null, null);
    }

    public ReliableMessageChatMemoryAdvisor(
            ChatMemory chatMemory,
            String defaultConversationId,
            int order,
            Scheduler scheduler,
            LlmMessageTraceSupport traceSupport,
            String traceId
    ) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(scheduler, "scheduler cannot be null");
        this.chatMemory = chatMemory;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
        this.scheduler = scheduler;
        this.traceSupport = traceSupport;
        this.traceId = traceId;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String conversationId = getConversationId(chatClientRequest.context(), this.defaultConversationId);

        List<Message> memoryMessages = new ArrayList<>(this.chatMemory.get(conversationId));
        List<Message> currentInstructions = chatClientRequest.prompt().getInstructions();
        List<Message> messages = new ArrayList<>(memoryMessages);
        messages.addAll(currentInstructions);
        ChatClientRequest requestWithMemory = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(messages).build())
                .build();
        if (traceSupport != null) {
            traceSupport.recordFinalMessages(traceId, conversationId, memoryMessages, currentInstructions, messages);
        }

        UserMessage userMessage = requestWithMemory.prompt().getUserMessage();
        this.chatMemory.add(conversationId, userMessage);

        log.debug("[ChatMemory] 已注入历史并写入用户消息, conversationId={}", conversationId);

        return requestWithMemory;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        List<Message> assistantMessages = List.of();
        if (chatClientResponse.chatResponse() != null) {
            assistantMessages = chatClientResponse.chatResponse()
                    .getResults()
                    .stream()
                    .map(g -> (Message) g.getOutput())
                    .filter(Objects::nonNull)
                    .filter(message -> message.getText() != null && !message.getText().isBlank())
                    .toList();
        }
        String conversationId = getConversationId(chatClientResponse.context(), this.defaultConversationId);
        if (!assistantMessages.isEmpty()) {
            this.chatMemory.add(conversationId, assistantMessages);
        }

        log.debug("[ChatMemory] 已写入助手回复, conversationId={}", conversationId);

        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return Mono.just(chatClientRequest)
                .publishOn(this.getScheduler())
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(
                        flux, response -> this.after(response, streamAdvisorChain)));
    }

    private String getConversationId(Map<String, Object> context, String defaultId) {
        Object cid = context.get(ChatMemory.CONVERSATION_ID);
        return (cid != null && !cid.toString().isBlank()) ? cid.toString() : defaultId;
    }

    public static Builder builder(ChatMemory chatMemory) {
        return new Builder(chatMemory);
    }

    public static final class Builder {

        private String conversationId = ChatMemory.DEFAULT_CONVERSATION_ID;

        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

        private ChatMemory chatMemory;

        private LlmMessageTraceSupport traceSupport;

        private String traceId;

        private Builder(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder traceSupport(LlmMessageTraceSupport traceSupport) {
            this.traceSupport = traceSupport;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public ReliableMessageChatMemoryAdvisor build() {
            return new ReliableMessageChatMemoryAdvisor(
                    this.chatMemory,
                    this.conversationId,
                    this.order,
                    this.scheduler,
                    this.traceSupport,
                    this.traceId
            );
        }
    }
}
