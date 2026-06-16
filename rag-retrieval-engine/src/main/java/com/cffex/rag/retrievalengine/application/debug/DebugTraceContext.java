package com.cffex.rag.retrievalengine.application.debug;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class DebugTraceContext {

    private final InheritableThreadLocal<String> traceId = new InheritableThreadLocal<>();

    public String currentTraceId() {
        return traceId.get();
    }

    public void setTraceId(String value) {
        traceId.set(Objects.requireNonNull(value, "traceId must not be null"));
    }

    public void clear() {
        traceId.remove();
    }
}
