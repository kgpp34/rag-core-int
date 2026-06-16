package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import java.util.function.Consumer;

/**
 * LLM 调用入口：供上层模块直接发起文本生成/对话请求。
 *
 * <p>接口只接收已解析好的模型端点与消息，不负责元数据查询与模型选择。
 */
public interface LlmService {
    LlmResponse generate(LlmRequest request);

    void streamGenerate(LlmRequest request, Consumer<LlmStreamChunk> chunkConsumer);
}
