package com.cffex.rag.retrievalengine.application.debug;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cffex.rag.retrievalengine.config.RetrievalDebugTraceProperties;
import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;
import com.cffex.rag.trace.domain.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RetrievalDebugTraceWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void recordWritesCompleteJsonLinesWhenCalledConcurrently() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DebugTraceContext traceContext = new DebugTraceContext();
        RetrievalDebugTraceWriter writer = new RetrievalDebugTraceWriter(
                new RetrievalDebugTraceProperties(true, tempDir.toString(), 200, true),
                traceContext,
                objectMapper
        );

        int threadCount = 12;
        int eventsPerThread = 40;
        try (var executor = Executors.newFixedThreadPool(threadCount)) {
            for (int thread = 0; thread < threadCount; thread++) {
                int threadIndex = thread;
                executor.submit(() -> {
                    traceContext.setTraceId("trace-1");
                    for (int event = 0; event < eventsPerThread; event++) {
                        writer.record("event-" + threadIndex + "-" + event, Map.of(
                                "thread", threadIndex,
                                "event", event,
                                "payload", "x".repeat(4096)
                        ));
                    }
                    traceContext.clear();
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Path eventsFile = tempDir.resolve("trace-1").resolve("events.jsonl");
        List<String> lines = Files.readAllLines(eventsFile);
        assertThat(lines).hasSize(threadCount * eventsPerThread);
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            assertThat(node.path("source").asText()).isEqualTo("rag-core-int");
            assertThat(node.path("traceId").asText()).isEqualTo("trace-1");
            assertThat(node.path("payload").path("payload").asText()).hasSize(4096);
        }
    }

    @Test
    void summaryModePersistsOnlyFailuresFromRetrievalEngineInternals() {
        ObjectMapper objectMapper = new ObjectMapper();
        DebugTraceContext traceContext = new DebugTraceContext();
        traceContext.setTraceId("trace-summary");
        RecordingTraceRecorder traceRecorder = new RecordingTraceRecorder();
        TraceProperties traceProperties = new TraceProperties();
        traceProperties.setEnabled(true);
        traceProperties.setMode(TraceProperties.Mode.SUMMARY);

        RetrievalDebugTraceWriter writer = new RetrievalDebugTraceWriter(
                new RetrievalDebugTraceProperties(false, tempDir.toString(), 200, false),
                traceContext,
                objectMapper,
                traceRecorder,
                traceProperties
        );

        writer.record("retrieval.request", Map.of());
        writer.record("kb.recall.started", Map.of());
        writer.record("milvus.dense.request", Map.of());
        writer.record("kb.dense.raw", Map.of());
        writer.record("global.final", Map.of());
        writer.record("milvus.dense.failed", Map.of("error", "boom"));

        assertThat(traceRecorder.events)
                .extracting(TraceEvent::eventName)
                .containsExactly("milvus.dense.failed");
    }

    @Test
    void detailModePersistsKnowledgeBaseEventsButSkipsRawDebugEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        DebugTraceContext traceContext = new DebugTraceContext();
        traceContext.setTraceId("trace-detail");
        RecordingTraceRecorder traceRecorder = new RecordingTraceRecorder();
        TraceProperties traceProperties = new TraceProperties();
        traceProperties.setEnabled(true);
        traceProperties.setMode(TraceProperties.Mode.DETAIL);

        RetrievalDebugTraceWriter writer = new RetrievalDebugTraceWriter(
                new RetrievalDebugTraceProperties(false, tempDir.toString(), 200, false),
                traceContext,
                objectMapper,
                traceRecorder,
                traceProperties
        );

        writer.record("kb.recall.started", Map.of());
        writer.record("kb.final", Map.of());
        writer.record("kb.dense.raw", Map.of());
        writer.record("milvus.sparse.response", Map.of());

        assertThat(traceRecorder.events)
                .extracting(TraceEvent::eventName)
                .containsExactly("kb.recall.started", "kb.final");
    }

    private static final class RecordingTraceRecorder implements TraceRecorder {
        private final List<TraceEvent> events = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void record(TraceEvent event) {
            events.add(event);
        }
    }
}
