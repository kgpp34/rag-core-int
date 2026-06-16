package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import org.springframework.ai.chat.client.ChatClient;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

/**
 * ChatClient 工厂。
 */
public interface ChatClientFactory {

    ChatClient create(ChatCompletionRequest request);
}
