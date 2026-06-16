package com.cffex.rag.retrievalengine.application.acl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cffex.rag.common.domain.llm.LlmMessage;
import com.cffex.rag.common.domain.llm.LlmMessageRole;
import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionChunk;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionResponse;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.model.ChatMessageRole;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

/**
 * LLM 共享模型与 retrieval-engine 聊天领域模型之间的 ACL 映射。
 */
@Component
public class LlmChatCompletionMapper {

    public ChatCompletionRequest toChatCompletionRequest(LlmRequest request) {
        return new ChatCompletionRequest(
                new ChatModelPolicy(
                        request.modelEndpoint().endpoint(),
                        request.modelEndpoint().authToken(),
                        request.modelEndpoint().model()
                ),
                request.messages().stream().map(this::toChatMessage).toList(),
                request.temperature(),
                request.maxTokens(),
                request.userId(),
                request.conversationId()
        );
    }

    public LlmResponse toLlmResponse(ChatCompletionResponse response) {
        return new LlmResponse(
                response.content(),
                response.finishReason(),
                response.metadata()
        );
    }

    public LlmStreamChunk toLlmStreamChunk(ChatCompletionChunk chunk) {
        return new LlmStreamChunk(
                chunk.delta(),
                chunk.finishReason(),
                chunk.completed(),
                chunk.metadata()
        );
    }

    private ChatMessage toChatMessage(LlmMessage message) {
        return new ChatMessage(
                switch (message.role()) {
                    case SYSTEM -> ChatMessageRole.SYSTEM;
                    case USER -> ChatMessageRole.USER;
                    case ASSISTANT -> ChatMessageRole.ASSISTANT;
                },
                message.content()
        );
    }

    public List<LlmMessage> toLlmMessages(List<ChatMessage> messages) {
        return messages.stream().map(this::toLlmMessage).toList();
    }

    private LlmMessage toLlmMessage(ChatMessage message) {
        return new LlmMessage(
                switch (message.role()) {
                    case SYSTEM -> LlmMessageRole.SYSTEM;
                    case USER -> LlmMessageRole.USER;
                    case ASSISTANT -> LlmMessageRole.ASSISTANT;
                },
                message.content()
        );
    }
}
