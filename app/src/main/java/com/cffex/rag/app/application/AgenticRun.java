package com.cffex.rag.app.application;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Core-side durable view of an Agentic run. */
public record AgenticRun(
        UUID runId,
        String requestId,
        String conversationId,
        String userId,
        String status,
        String query,
        JsonNode output,
        JsonNode error,
        String memoryStatus,
        int memoryAttempts,
        String memoryError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
