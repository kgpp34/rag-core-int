package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

class TokenBudgetChatMemoryTest {

    private static final String CONVERSATION_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    @Test
    void get_returnsRecentMessagesWithinBudgetWhileRepositoryKeepsCompleteHistory() {
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        TokenBudgetChatMemory memory = memory(repository, new InMemorySummaryRepository(), 18, 2);
        List<Message> history = List.of(
                new UserMessage("q1"),
                new AssistantMessage("a1"),
                new UserMessage("q2"),
                new AssistantMessage("a2"),
                new UserMessage("q3")
        );

        memory.add(CONVERSATION_ID, history);

        assertThat(memory.get(CONVERSATION_ID))
                .extracting(Message::getText)
                .containsExactly("q2", "a2", "q3");
        assertThat(repository.findByConversationId(CONVERSATION_ID))
                .extracting(Message::getText)
                .containsExactly("q1", "a1", "q2", "a2", "q3");
    }

    @Test
    void get_returnsEmptyHistoryWhenNoBudgetRemains() {
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        TokenBudgetChatMemory memory = memory(repository, new InMemorySummaryRepository(), 0, 10);
        memory.add(CONVERSATION_ID, new UserMessage("hello"));

        assertThat(memory.get(CONVERSATION_ID)).isEmpty();
        assertThat(repository.findByConversationId(CONVERSATION_ID)).hasSize(1);
    }

    @Test
    void get_injectsSummaryAndOnlyUnsummarizedRecentMessages() {
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        InMemorySummaryRepository summaryRepository = new InMemorySummaryRepository();
        summaryRepository.save(new ConversationSummary(CONVERSATION_ID, "earlier facts", 2));
        TokenBudgetChatMemory memory = memory(repository, summaryRepository, 100, 10);
        memory.add(CONVERSATION_ID, List.of(
                new UserMessage("old question"),
                new AssistantMessage("old answer"),
                new UserMessage("recent question")
        ));

        assertThat(memory.get(CONVERSATION_ID))
                .extracting(Message::getText)
                .containsExactly("以下是较早对话的摘要：\nearlier facts", "recent question");
    }

    private TokenBudgetChatMemory memory(
            InMemoryChatMemoryRepository repository,
            InMemorySummaryRepository summaryRepository,
            int maxTokens,
            int memoryWindowTurns
    ) {
        ChatProperties properties = new ChatProperties(true, memoryWindowTurns, 32768, 0.2d, false, 10, 512, false);
        ConversationSummaryService summaryService = new ConversationSummaryService(
                repository, summaryRepository, (policy, summary, messages, tokens) -> "", properties, Runnable::run);
        return new TokenBudgetChatMemory(
                repository,
                summaryRepository,
                summaryService,
                new ChatModelPolicy("https://llm.example.com", "token", "model"),
                new TextLengthEstimator(),
                maxTokens,
                memoryWindowTurns
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
