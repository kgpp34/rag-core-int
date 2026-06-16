package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

/**
 * 聚合多个 Advisor 提供器。
 */
@Component
@Primary
public class CompositeChatAdvisorProvider implements ChatAdvisorProvider {

    private final List<ChatAdvisorContributor> contributors;

    public CompositeChatAdvisorProvider(List<ChatAdvisorContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    @Override
    public List<Advisor> advisors(ChatCompletionRequest request) {
        return contributors.stream()
                .flatMap(contributor -> contributor.advisors(request).stream())
                .toList();
    }
}
