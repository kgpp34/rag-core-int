package com.cffex.rag.trace.application;

import java.time.Instant;
import java.util.Map;

import com.cffex.rag.trace.domain.TraceEvent;

public interface TraceRecorder {

    boolean enabled();

    void record(TraceEvent event);

    default void record(
            String traceId,
            String requestId,
            String source,
            String stage,
            String eventName,
            Map<String, Object> payload
    ) {
        record(new TraceEvent(traceId, requestId, source, stage, eventName, Instant.now(), payload));
    }
}
