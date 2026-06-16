package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SpringAiOpenAiCompatibleChatClientFactoryTest {

    @Test
    void resolveEndpoint_keepsHostOnlyBaseUrl() {
        SpringAiOpenAiCompatibleChatClientFactory.EndpointResolution resolution =
                SpringAiOpenAiCompatibleChatClientFactory.resolveEndpoint("https://api.openai.com");

        assertThat(resolution.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(resolution.completionsPath()).isEqualTo("/chat/completions");
    }

    @Test
    void resolveEndpoint_keepsCustomGatewayBasePath() {
        SpringAiOpenAiCompatibleChatClientFactory.EndpointResolution resolution =
                SpringAiOpenAiCompatibleChatClientFactory.resolveEndpoint("https://gateway.example.com/proxy/openai/chat/completions");

        assertThat(resolution.baseUrl()).isEqualTo("https://gateway.example.com/proxy/openai");
        assertThat(resolution.completionsPath()).isEqualTo("/chat/completions");
    }

    @Test
    void resolveEndpoint_preservesProviderSpecificV1PrefixInBaseUrl() {
        SpringAiOpenAiCompatibleChatClientFactory.EndpointResolution resolution =
                SpringAiOpenAiCompatibleChatClientFactory.resolveEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertThat(resolution.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(resolution.completionsPath()).isEqualTo("/chat/completions");
    }
}
