package com.cffex.rag.retrievalengine.application;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import com.cffex.rag.common.service.LlmService;
import com.cffex.rag.retrievalengine.application.acl.LlmChatCompletionMapper;
import com.cffex.rag.retrievalengine.domain.port.ChatCompletionPort;

/** 对上暴露的 LLM 应用服务。 */
@Service
public class DefaultLlmService implements LlmService {

    private final ChatCompletionPort chatCompletionPort;
    private final LlmChatCompletionMapper llmChatCompletionMapper;

    public DefaultLlmService(
            ChatCompletionPort chatCompletionPort,
            LlmChatCompletionMapper llmChatCompletionMapper
    ) {
        this.chatCompletionPort = Objects.requireNonNull(chatCompletionPort);
        this.llmChatCompletionMapper = Objects.requireNonNull(llmChatCompletionMapper);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        // 上游仍然使用 rag-common 的 LLM 请求模型；
        // 应用层先经过 ACL 映射为领域请求，再交给出站端口。
        return llmChatCompletionMapper.toLlmResponse(
                chatCompletionPort.generate(llmChatCompletionMapper.toChatCompletionRequest(request))
        );
    }

    @Override
    public void streamGenerate(LlmRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        // 流式场景同样只保留 ACL + 端口编排，不把 Spring AI 细节泄漏给上层调用方。
        chatCompletionPort.streamGenerate(
                llmChatCompletionMapper.toChatCompletionRequest(request),
                chunk -> chunkConsumer.accept(llmChatCompletionMapper.toLlmStreamChunk(chunk))
        );
    }
}
