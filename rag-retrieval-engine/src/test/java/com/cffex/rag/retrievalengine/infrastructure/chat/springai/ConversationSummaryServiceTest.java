package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

class ConversationSummaryServiceTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final ChatModelPolicy MODEL_POLICY =
            new ChatModelPolicy("https://llm.example.com", "token", "model");

    @Test
    void scheduleUpdate_summarizesOldMessagesAndRetainsRecentTail() {
        InMemoryChatMemoryRepository memoryRepository = new InMemoryChatMemoryRepository();
        memoryRepository.saveAll(CONVERSATION_ID, List.of(
                new UserMessage("q1"), new AssistantMessage("a1"),
                new UserMessage("q2"), new AssistantMessage("a2"),
                new UserMessage("q3"), new AssistantMessage("a3")
        ));
        InMemorySummaryRepository summaryRepository = new InMemorySummaryRepository();
        CapturingSummarizer summarizer = new CapturingSummarizer();
        ConversationSummaryService service = new ConversationSummaryService(
                memoryRepository,
                summaryRepository,
                summarizer,
                new ChatProperties(true, 32768, 1024, true, 4, 2, 256, false),
                Runnable::run
        );

        service.scheduleUpdate(CONVERSATION_ID, MODEL_POLICY);

        assertThat(summarizer.messages).extracting(Message::getText)
                .containsExactly("q1", "a1", "q2", "a2");
        assertThat(summaryRepository.summary.content()).isEqualTo("updated summary");
        assertThat(summaryRepository.summary.summarizedMessageCount()).isEqualTo(4);
    }

    @Test
    void scheduleUpdate_doesNothingBelowThreshold() {
        InMemoryChatMemoryRepository memoryRepository = new InMemoryChatMemoryRepository();
        memoryRepository.saveAll(CONVERSATION_ID, List.of(
                new UserMessage("q1"), new AssistantMessage("a1"),
                new UserMessage("q2")
        ));
        InMemorySummaryRepository summaryRepository = new InMemorySummaryRepository();
        CapturingSummarizer summarizer = new CapturingSummarizer();
        ConversationSummaryService service = new ConversationSummaryService(
                memoryRepository,
                summaryRepository,
                summarizer,
                new ChatProperties(true, 32768, 1024, true, 4, 2, 256, false),
                Runnable::run
        );

        service.scheduleUpdate(CONVERSATION_ID, MODEL_POLICY);

        assertThat(summarizer.messages).isEmpty();
        assertThat(summaryRepository.summary).isNull();
    }

    private static final class CapturingSummarizer implements ConversationSummarizer {

        private List<Message> messages = List.of();

        @Override
        public String summarize(ChatModelPolicy modelPolicy, String previousSummary, List<Message> messages,
                int maxTokens) {
            this.messages = List.copyOf(messages);
            return "updated summary";
        }
    }

    private static final class InMemorySummaryRepository implements ConversationSummaryRepository {

        private ConversationSummary summary;

        @Override
        public Optional<ConversationSummary> findByConversationId(String conversationId) {
            return Optional.ofNullable(summary);
        }

        @Override
        public void save(ConversationSummary summary) {
            this.summary = summary;
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            summary = null;
        }
    }
}
