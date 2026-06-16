package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

/**
 * 按请求提供 ChatClient Advisor。
 */
public interface ChatAdvisorProvider {

    List<Advisor> advisors(ChatCompletionRequest request);
}
