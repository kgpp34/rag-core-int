package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.model.ChatMessageRole;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

class ChatMemoryAdvisorContributorTest {

    @Test
    void resolveMemoryTokenBudget_reservesSpaceForHistoryWithNormalOutputLimit() {
        ChatMemoryAdvisorContributor contributor = contributor();

        int budget = contributor.resolveMemoryTokenBudget(request("current prompt", 4096));

        assertThat(budget).isEqualTo(27630);
    }

    @Test
    void resolveMemoryTokenBudget_returnsZeroWhenCurrentRequestConsumesContextWindow() {
        ChatMemoryAdvisorContributor contributor = contributor();

        int budget = contributor.resolveMemoryTokenBudget(request("x".repeat(30000), 4096));

        assertThat(budget).isZero();
    }

    private ChatMemoryAdvisorContributor contributor() {
        return new ChatMemoryAdvisorContributor(
                null,
                null,
                null,
                new ChatProperties(true, 32768, 1024, true, 12, 6, 1024, false),
                new TextLengthEstimator()
        );
    }

    private ChatCompletionRequest request(String prompt, int maxTokens) {
        return new ChatCompletionRequest(
                new ChatModelPolicy("https://llm.example.com", "token", "model"),
                List.of(new ChatMessage(ChatMessageRole.USER, prompt)),
                0.2d,
                maxTokens,
                "user-1",
                "conv-1"
        );
    }

    private static final class TextLengthEstimator implements TokenCountEstimator {

        @Override
        public int estimate(String text) {
            return text.length();
        }

        @Override
        public int estimate(org.springframework.ai.content.MediaContent content) {
            return estimate(content.getText());
        }

        @Override
        public int estimate(Iterable<org.springframework.ai.content.MediaContent> contents) {
            int total = 0;
            for (org.springframework.ai.content.MediaContent content : contents) {
                total += estimate(content);
            }
            return total;
        }
    }
}
