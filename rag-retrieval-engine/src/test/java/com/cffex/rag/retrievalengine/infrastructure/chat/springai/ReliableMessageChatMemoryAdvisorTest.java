package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ReliableMessageChatMemoryAdvisorTest {

    @Test
    void after_skipsAssistantMessagesWithNullContent() {
        CapturingChatMemory chatMemory = new CapturingChatMemory();
        ReliableMessageChatMemoryAdvisor advisor = ReliableMessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                .build();
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(null)))))
                .build();

        advisor.after(response, null);

        assertThat(chatMemory.addedMessages).isEmpty();
    }

    private static final class CapturingChatMemory implements ChatMemory {

        private final List<Message> addedMessages = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            addedMessages.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(addedMessages);
        }

        @Override
        public void clear(String conversationId) {
            addedMessages.clear();
        }
    }
}
