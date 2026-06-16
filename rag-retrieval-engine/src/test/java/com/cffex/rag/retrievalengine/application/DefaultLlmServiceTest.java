package com.cffex.rag.retrievalengine.application;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.llm.LlmMessage;
import com.cffex.rag.common.domain.llm.LlmMessageRole;
import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.retrievalengine.application.acl.LlmChatCompletionMapper;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionResponse;
import com.cffex.rag.retrievalengine.domain.port.ChatCompletionPort;

@ExtendWith(MockitoExtension.class)
class DefaultLlmServiceTest {

    @Mock
    private ChatCompletionPort chatCompletionPort;

    @Test
    void generate_delegatesToChatCompletionPort() {
        DefaultLlmService service = new DefaultLlmService(chatCompletionPort, new LlmChatCompletionMapper());
        LlmRequest request = new LlmRequest(
                new ModelEndpointSpec("https://llm.example.com", "token", "gpt-test"),
                List.of(new LlmMessage(LlmMessageRole.USER, "hello")),
                0.2,
                256,
                null,
                null
        );
        LlmResponse expected = new LlmResponse("hi", "stop", Map.of("model", "gpt-test"));

        when(chatCompletionPort.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChatCompletionResponse("hi", "stop", Map.of("model", "gpt-test")));

        LlmResponse actual = service.generate(request);

        assertThat(actual).isEqualTo(expected);
        verify(chatCompletionPort).generate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void streamGenerate_delegatesToChatCompletionPort() {
        DefaultLlmService service = new DefaultLlmService(chatCompletionPort, new LlmChatCompletionMapper());
        LlmRequest request = new LlmRequest(
                new ModelEndpointSpec("https://llm.example.com", "token", "gpt-test"),
                List.of(new LlmMessage(LlmMessageRole.USER, "hello")),
                0.2,
                256,
                null,
                null
        );
        Consumer<LlmStreamChunk> consumer = chunk -> {
        };

        service.streamGenerate(request, consumer);

        verify(chatCompletionPort).streamGenerate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
