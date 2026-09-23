package com.cffex.rag.app.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Persists the core-side lifecycle and conversation association of an Agentic run. */
public interface AgenticRunStore {

    void create(UUID runId, String requestId, String conversationId, String userId, String query);

    void markRunning(UUID runId);

    void markCompleted(UUID runId, JsonNode output);

    void markFailed(UUID runId, JsonNode error);

    void markCancelled(UUID runId);

    boolean claimMemoryWrite(UUID runId, Instant staleBefore);

    void markMemoryWritten(UUID runId);

    void markMemoryWriteFailed(UUID runId, String error);

    Optional<AgenticRun> findByRunId(UUID runId);

    List<AgenticRun> findByConversationId(String conversationId, int limit);

    List<AgenticRun> findRecoverable(Instant staleBefore, int maxMemoryAttempts, int limit);
}
