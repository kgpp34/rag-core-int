package com.cffex.rag.retrievalengine.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
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
}
