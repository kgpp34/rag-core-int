package com.cffex.rag.app.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.service.ConversationMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgenticRunReconcilerTest {

    @Test
    void completedRemoteRunIsPersistedAndMemoryIsCompensated() throws Exception {
        AgenticRunStore store = mock(AgenticRunStore.class);
        AgenticRagClient client = mock(AgenticRagClient.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AppProperties properties = new AppProperties();
        UUID runId = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(300);
        AgenticRun run = new AgenticRun(
                runId, "request-1", "conversation-1", "user-1", "running", "question",
                null, null, "pending", 0, null, old, old, null
        );
        ObjectMapper mapper = new ObjectMapper();
        var output = mapper.readTree("{\"answer\":\"answer\",\"citations\":[]}");
        when(store.findRecoverable(any(), eq(5), eq(50))).thenReturn(List.of(run));
        when(client.getRun(runId, "request-1"))
                .thenReturn(new AgenticRagClient.AgenticRunResult("completed", output, null));
        when(store.claimMemoryWrite(eq(runId), any())).thenReturn(true);

        new AgenticRunReconciler(store, client, memory, properties).reconcile();

        verify(store).markCompleted(runId, output);
        verify(memory).appendExchangeOnce(runId.toString(), "conversation-1", "question", "answer");
        verify(store).markMemoryWritten(runId);
    }
}
