package com.cffex.rag.trace.domain;

import java.util.List;

public interface TraceSink {

    void write(List<TraceEvent> events);
}
