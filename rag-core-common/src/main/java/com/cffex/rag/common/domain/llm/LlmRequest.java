package com.cffex.rag.common.domain.llm;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;

/** 上层发往 LLM 的请求。 */
public record LlmRequest(
        ModelEndpointSpec modelEndpoint,
        List<LlmMessage> messages,
        Double temperature,
        Integer maxTokens,
        String userId,
        String conversationId
) {
    public LlmRequest {
        modelEndpoint = Objects.requireNonNull(modelEndpoint, "modelEndpoint must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (temperature != null && temperature < 0.0d) {
            throw new IllegalArgumentException("temperature must not be negative");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public static LlmRequest singleTurn(ModelEndpointSpec modelEndpoint, String userPrompt) {
        return new LlmRequest(
                modelEndpoint,
                List.of(new LlmMessage(LlmMessageRole.USER, userPrompt)),
                null,
                null,
                null,
                null
        );
    }
}
