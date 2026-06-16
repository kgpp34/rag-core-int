package com.cffex.rag.retrievalengine.domain.port;

import java.util.function.Consumer;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionChunk;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionResponse;

/**
 * 聊天补全出站端口。
 */
public interface ChatCompletionPort {

    ChatCompletionResponse generate(ChatCompletionRequest request);

    void streamGenerate(ChatCompletionRequest request, Consumer<ChatCompletionChunk> chunkConsumer);
}
