package com.cffex.rag.retrievalengine.application;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMessage;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

class DefaultConversationMemoryServiceTest {

    @Test
    void resolve_returnsDefaultContextWhenMemoryDisabled() {
        DefaultConversationMemoryService service = new DefaultConversationMemoryService();

        ConversationMemoryContext context = service.resolve(new ConversationMemoryRequest(
                "user-1", false, null));

        assertEquals("user-1", context.userId());
        assertNull(context.conversationId());
    }

    @Test
    void resolve_returnsClientConversationIdWhenMemoryEnabled() {
        DefaultConversationMemoryService service = new DefaultConversationMemoryService();

        ConversationMemoryContext context = service.resolve(new ConversationMemoryRequest(
                "user-1", true, "conv-1"));

        assertEquals("user-1", context.userId());
        assertEquals("conv-1", context.conversationId());
    }

    @Test
    void resolve_rejectsBlankConversationIdWhenMemoryEnabled() {
        DefaultConversationMemoryService service = new DefaultConversationMemoryService();

        RagServiceException ex = assertThrows(RagServiceException.class, () -> service.resolve(
                new ConversationMemoryRequest("user-1", true, " ")));

        assertEquals(RagErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    void recentUserMessages_returnsRecentUserMessagesInChronologicalOrder() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        when(repository.findByConversationId("conv-1")).thenReturn(List.of(
                new UserMessage("first"),
                new AssistantMessage("assistant"),
                new UserMessage("second"),
                new UserMessage("third")
        ));
        DefaultConversationMemoryService service = new DefaultConversationMemoryService(repository);

        List<String> messages = service.recentUserMessages("conv-1", 2);

        assertEquals(List.of("second", "third"), messages);
    }

    @Test
    void recentUserMessages_extractsOriginalQuestionFromRagPrompt() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        when(repository.findByConversationId("conv-1")).thenReturn(List.of(
                new UserMessage("""
                        请基于下面检索到的知识片段回答用户问题。

                        用户问题：项目上党办会的要求是什么样子的？

                        知识片段：
                        [片段 1]
                        这里是很长的召回内容
                        """),
                new AssistantMessage("assistant")
        ));
        DefaultConversationMemoryService service = new DefaultConversationMemoryService(repository);

        List<String> messages = service.recentUserMessages("conv-1", 3);

        assertEquals(List.of("项目上党办会的要求是什么样子的？"), messages);
    }

    @Test
    void recentMessagesReturnsUserAndAssistantHistory() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        when(repository.findByConversationId("conv-1")).thenReturn(List.of(
                new UserMessage("question"),
                new AssistantMessage("answer")
        ));
        DefaultConversationMemoryService service = new DefaultConversationMemoryService(repository);

        List<ConversationMessage> messages = service.recentMessages("conv-1", 10);

        assertEquals(List.of(
                new ConversationMessage("user", "question"),
                new ConversationMessage("assistant", "answer")
        ), messages);
    }

    @Test
    void appendExchangePersistsCompleteHistory() {
        ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
        when(repository.findByConversationId("conv-1")).thenReturn(List.of(new UserMessage("old question")));
        DefaultConversationMemoryService service = new DefaultConversationMemoryService(repository);

        service.appendExchange("conv-1", "new question", "new answer");

        verify(repository).saveAll(org.mockito.ArgumentMatchers.eq("conv-1"), org.mockito.ArgumentMatchers.argThat(
                messages -> messages.size() == 3
                        && messages.get(1).getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
                        && messages.get(2).getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT
        ));
    }
}
