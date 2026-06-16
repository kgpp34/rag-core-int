package com.cffex.rag.trace.application;

import com.cffex.rag.trace.domain.TraceEvent;

public final class NoOpTraceRecorder implements TraceRecorder {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void record(TraceEvent event) {
    }
}
