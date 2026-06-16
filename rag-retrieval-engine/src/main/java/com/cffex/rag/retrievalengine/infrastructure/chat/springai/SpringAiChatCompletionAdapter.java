package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionChunk;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionResponse;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.port.ChatCompletionPort;

@Component
public class SpringAiChatCompletionAdapter implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatCompletionAdapter.class);

    private final ChatClientFactory chatClientFactory;
    private final ChatAdvisorProvider chatAdvisorProvider;

    public SpringAiChatCompletionAdapter(
            ChatClientFactory chatClientFactory,
            ChatAdvisorProvider chatAdvisorProvider
    ) {
        this.chatClientFactory = Objects.requireNonNull(chatClientFactory);
        this.chatAdvisorProvider = Objects.requireNonNull(chatAdvisorProvider);
    }

    @Override
    public ChatCompletionResponse generate(ChatCompletionRequest request) {
        // 同步补全链路：
        // 领域请求 -> Spring AI ChatClient -> 领域响应。
        ChatResponse chatResponse = buildRequestSpec(request)
                .call()
                .chatResponse();
        return new ChatCompletionResponse(
                assistantText(chatResponse),
                finishReason(chatResponse),
                responseMetadata(chatResponse, request)
        );
    }

    @Override
    public void streamGenerate(ChatCompletionRequest request, Consumer<ChatCompletionChunk> chunkConsumer) {
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<Map<String, Object>> lastMetadata = new AtomicReference<>(Map.of());
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        buildRequestSpec(request)
                .stream()
                .chatResponse()
                .doOnNext(chatResponse -> {
                    String delta = assistantText(chatResponse);
                    Map<String, Object> metadata = responseMetadata(chatResponse, request);
                    lastMetadata.set(metadata);
                    String currentFinishReason = finishReason(chatResponse);
                    if (currentFinishReason != null && !currentFinishReason.isBlank()) {
                        finishReason.set(currentFinishReason);
                    }
                    if (!delta.isEmpty()) {
                        chunkConsumer.accept(new ChatCompletionChunk(delta, null, false, metadata));
                    }
                })
                .doOnError(errorRef::set)
                .doFinally(signalType -> completionLatch.countDown())
                .subscribe(ignored -> {
                }, ignored -> {
                    // The error is rethrown on the calling thread after the latch is released.
                });

        try {
            completionLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("流式生成被中断", e);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("流式生成失败", error);
        }

        chunkConsumer.accept(new ChatCompletionChunk(
                "",
                finishReason.get() == null ? "stop" : finishReason.get(),
                true,
                lastMetadata.get().isEmpty() ? Map.of("model", request.modelPolicy().model()) : lastMetadata.get()
        ));
    }

    private ChatClient.ChatClientRequestSpec buildRequestSpec(ChatCompletionRequest request) {
        List<Message> springAiMessages = toSpringAiMessages(request.messages());
        log.info("[ChatMemory] buildRequestSpec: messageCount={}, messageTypes={}",
                springAiMessages.size(),
                springAiMessages.stream().map(m -> m.getMessageType().name()).toList());

        ChatClient.ChatClientRequestSpec requestSpec = chatClientFactory.create(request)
                .prompt()
                .messages(springAiMessages);
        List<Advisor> advisors = chatAdvisorProvider.advisors(request);
        log.info("[ChatMemory] buildRequestSpec: advisorCount={}, advisorTypes={}",
                advisors.size(),
                advisors.stream().map(a -> a.getClass().getSimpleName()).toList());
        if (!advisors.isEmpty()) {
            requestSpec = requestSpec.advisors(spec -> {
                spec.advisors(advisors);
                if (request.conversationId() != null && !request.conversationId().isBlank()) {
                    spec.param(ChatMemory.CONVERSATION_ID, request.conversationId());
                }
            });
        }
        return requestSpec;
    }

    private List<Message> toSpringAiMessages(List<ChatMessage> messages) {
        return messages.stream().map(this::toSpringAiMessage).toList();
    }

    private Message toSpringAiMessage(ChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }

    private static String finishReason(ChatResponse chatResponse) {
        if (chatResponse.getResult() == null) {
            return "stop";
        }
        ChatGenerationMetadata metadata = chatResponse.getResult().getMetadata();
        if (metadata == null || metadata.getFinishReason() == null || metadata.getFinishReason().isBlank()) {
            return "stop";
        }
        return metadata.getFinishReason();
    }

    private static String assistantText(ChatResponse chatResponse) {
        if (chatResponse.getResult() == null) {
            return "";
        }
        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output == null) {
            return "";
        }
        String text = output.getText();
        return text == null ? "" : text;
    }

    private static Map<String, Object> responseMetadata(ChatResponse chatResponse, ChatCompletionRequest request) {
        // 这里统一抽取模型名和 token usage，供上层直接回传给调用方或落调试信息。
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatResponseMetadata responseMetadata = chatResponse.getMetadata();
        if (responseMetadata != null) {
            if (responseMetadata.getModel() != null) {
                metadata.put("model", responseMetadata.getModel());
            }
            Usage usage = responseMetadata.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) {
                    metadata.put("prompt_tokens", usage.getPromptTokens());
                }
                if (usage.getCompletionTokens() != null) {
                    metadata.put("completion_tokens", usage.getCompletionTokens());
                }
                if (usage.getTotalTokens() != null) {
                    metadata.put("total_tokens", usage.getTotalTokens());
                }
            }
        }
        metadata.putIfAbsent("model", request.modelPolicy().model());
        return Map.copyOf(metadata);
    }
}
