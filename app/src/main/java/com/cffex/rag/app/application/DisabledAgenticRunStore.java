package com.cffex.rag.app.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

final class DisabledAgenticRunStore implements AgenticRunStore {

    @Override
    public void create(UUID runId, String requestId, String conversationId, String userId, String query) {
    }

    @Override
    public void markRunning(UUID runId) {
    }

    @Override
    public void markCompleted(UUID runId, JsonNode output) {
    }

    @Override
    public void markFailed(UUID runId, JsonNode error) {
    }

    @Override
    public void markCancelled(UUID runId) {
    }

    @Override
    public boolean claimMemoryWrite(UUID runId, Instant staleBefore) {
        return true;
    }

    @Override
    public void markMemoryWritten(UUID runId) {
    }

    @Override
    public void markMemoryWriteFailed(UUID runId, String error) {
    }

    @Override
    public Optional<AgenticRun> findByRunId(UUID runId) {
        return Optional.empty();
    }

    @Override
    public List<AgenticRun> findByConversationId(String conversationId, int limit) {
        return List.of();
    }

    @Override
    public List<AgenticRun> findRecoverable(Instant staleBefore, int maxMemoryAttempts, int limit) {
        return List.of();
    }
}
