package com.cffex.rag.app.application;

import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/** Internal gateway for the asynchronous rag-agentic-int run protocol. */
public interface AgenticRagClient {

    UUID createRun(List<AgenticMessage> messages, List<String> docIds, String requestId);

    void streamEvents(UUID runId, Consumer<AgenticEvent> eventConsumer, String requestId);

    AgenticRunResult getRun(UUID runId, String requestId);

    void cancelRun(UUID runId, String requestId);

    record AgenticEvent(String id, String eventType, JsonNode data) {
    }

    record AgenticRunResult(String status, JsonNode output, JsonNode error) {
    }

    record AgenticMessage(String role, String content) {
    }
}
