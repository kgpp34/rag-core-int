package com.cffex.rag.trace.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TraceEvent(
        String traceId,
        String requestId,
        String source,
        String stage,
        String eventName,
        Instant occurredAt,
        Map<String, Object> payload
) {
    public TraceEvent {
        traceId = normalizeRequired(traceId, "unknown");
        requestId = normalize(requestId);
        source = normalizeRequired(source, "rag-core-int");
        stage = normalizeRequired(stage, "unknown");
        eventName = normalizeRequired(eventName, "unknown");
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    private static String normalizeRequired(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
