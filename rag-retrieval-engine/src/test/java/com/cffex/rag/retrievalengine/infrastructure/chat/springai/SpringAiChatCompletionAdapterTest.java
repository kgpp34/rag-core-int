package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionChunk;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionResponse;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.model.ChatMessageRole;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class SpringAiChatCompletionAdapterTest {

    @Mock
    private ChatClientFactory chatClientFactory;

    @Mock
    private ChatAdvisorProvider chatAdvisorProvider;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    @Mock
    private Advisor advisor;

    @Mock
    private ChatClient.AdvisorSpec advisorSpec;

    @Test
    void generate_usesChatClientAndMapsResponseMetadata() {
        SpringAiChatCompletionAdapter service = new SpringAiChatCompletionAdapter(chatClientFactory, chatAdvisorProvider);
        ChatCompletionRequest request = request();
        ChatResponse chatResponse = chatResponse("hello", "stop", "gpt-test", 12, 34);

        when(chatClientFactory.create(request)).thenReturn(chatClient);
        when(chatAdvisorProvider.advisors(request)).thenReturn(List.of(advisor));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

        ChatCompletionResponse response = service.generate(request);

        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.metadata())
                .containsEntry("model", "gpt-test")
                .containsEntry("prompt_tokens", 12)
                .containsEntry("completion_tokens", 34)
                .containsEntry("total_tokens", 46);
        verify(requestSpec).advisors(any(Consumer.class));
    }

    @Test
    void streamGenerate_emitsDeltasAndCompletedChunk() {
        SpringAiChatCompletionAdapter service = new SpringAiChatCompletionAdapter(chatClientFactory, chatAdvisorProvider);
        ChatCompletionRequest request = request();
        ChatResponse first = chatResponse("he", null, "gpt-test", null, null);
        ChatResponse second = chatResponse("llo", "stop", "gpt-test", null, null);
        java.util.ArrayList<ChatCompletionChunk> chunks = new java.util.ArrayList<>();
        Consumer<ChatCompletionChunk> consumer = chunks::add;

        when(chatClientFactory.create(request)).thenReturn(chatClient);
        when(chatAdvisorProvider.advisors(request)).thenReturn(List.of());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(first, second));

        service.streamGenerate(request, consumer);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).delta()).isEqualTo("he");
        assertThat(chunks.get(0).completed()).isFalse();
        assertThat(chunks.get(1).delta()).isEqualTo("llo");
        assertThat(chunks.get(2).completed()).isTrue();
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
    }

    @Test
    void streamGenerate_propagatesStreamErrors() {
        SpringAiChatCompletionAdapter service = new SpringAiChatCompletionAdapter(chatClientFactory, chatAdvisorProvider);
        ChatCompletionRequest request = request();
        IllegalStateException failure = new IllegalStateException("memory write failed");

        when(chatClientFactory.create(request)).thenReturn(chatClient);
        when(chatAdvisorProvider.advisors(request)).thenReturn(List.of());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.error(failure));

        assertThatThrownBy(() -> service.streamGenerate(request, ignored -> {
        })).isSameAs(failure);
    }

    @Test
    void generate_passesConversationIdThroughWithoutUserIdPrefix() {
        SpringAiChatCompletionAdapter service = new SpringAiChatCompletionAdapter(chatClientFactory, chatAdvisorProvider);
        String conversationId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ChatCompletionRequest request = request("user-1", conversationId);
        ChatResponse chatResponse = chatResponse("hello", "stop", "gpt-test", null, null);
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor = ArgumentCaptor.forClass(Consumer.class);

        when(chatClientFactory.create(request)).thenReturn(chatClient);
        when(chatAdvisorProvider.advisors(request)).thenReturn(List.of(advisor));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

        service.generate(request);
        verify(requestSpec).advisors(captor.capture());
        captor.getValue().accept(advisorSpec);

        verify(advisorSpec).param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId);
    }

    private ChatCompletionRequest request() {
        return request(null, null);
    }

    private ChatCompletionRequest request(String userId, String conversationId) {
        return new ChatCompletionRequest(
                new ChatModelPolicy("https://api.openai.com/v1", "token", "gpt-test"),
                List.of(
                        new ChatMessage(ChatMessageRole.SYSTEM, "system"),
                        new ChatMessage(ChatMessageRole.USER, "hello")
                ),
                0.3d,
                256,
                userId,
                conversationId
        );
    }

    private ChatResponse chatResponse(
            String content,
            String finishReason,
            String model,
            Integer promptTokens,
            Integer completionTokens
    ) {
        ChatGenerationMetadata generationMetadata = org.mockito.Mockito.mock(ChatGenerationMetadata.class);
        when(generationMetadata.getFinishReason()).thenReturn(finishReason);

        Generation generation = new Generation(new AssistantMessage(content), generationMetadata);
        ChatResponse chatResponse = org.mockito.Mockito.mock(ChatResponse.class);
        ChatResponseMetadata responseMetadata = org.mockito.Mockito.mock(ChatResponseMetadata.class);
        Usage usage = org.mockito.Mockito.mock(Usage.class);

        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(responseMetadata);
        when(responseMetadata.getModel()).thenReturn(model);
        when(responseMetadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        when(usage.getTotalTokens()).thenReturn(
                promptTokens == null || completionTokens == null ? null : promptTokens + completionTokens
        );
        return chatResponse;
    }
}
