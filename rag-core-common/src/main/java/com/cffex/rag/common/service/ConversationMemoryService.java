package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;

/** 解析会话标识，供上层构造 LLM 请求。 */
public interface ConversationMemoryService {

    ConversationMemoryContext resolve(ConversationMemoryRequest request);
}
