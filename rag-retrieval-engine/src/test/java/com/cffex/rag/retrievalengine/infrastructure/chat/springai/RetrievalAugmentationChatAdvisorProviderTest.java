package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.model.ChatMessageRole;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

class RetrievalAugmentationChatAdvisorProviderTest {

    @Test
    void advisors_returnsEmptyWhenFeatureDisabled() {
        RetrievalAugmentationChatAdvisorProvider provider = new RetrievalAugmentationChatAdvisorProvider(
                new ChatProperties(false, 32768, 1024, true, 12, 6, 1024, false),
                List.of(),
                Mockito.mock(MultiKnowledgeBaseDocumentRetriever.class)
        );

        assertThat(provider.advisors(request())).isEmpty();
    }

    @Test
    void advisors_buildsRetrievalAugmentationAdvisorWhenFeatureEnabled() {
        RetrievalAugmentationChatAdvisorProvider provider = new RetrievalAugmentationChatAdvisorProvider(
                new ChatProperties(false, 32768, 1024, true, 12, 6, 1024, true),
                List.of(Mockito.mock(QueryTransformer.class)),
                Mockito.mock(MultiKnowledgeBaseDocumentRetriever.class)
        );

        assertThat(provider.advisors(request())).hasSize(1);
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                new ChatModelPolicy("https://api.openai.com", "token", "gpt-test"),
                List.of(new ChatMessage(ChatMessageRole.USER, "hello")),
                0.2d,
                256,
                null,
                null
        );
    }
}
