package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

/**
 * 单个 Advisor 贡献者。
 */
public interface ChatAdvisorContributor {

    List<Advisor> advisors(ChatCompletionRequest request);
}
