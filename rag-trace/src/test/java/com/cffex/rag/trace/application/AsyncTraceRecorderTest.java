package com.cffex.rag.trace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cffex.rag.trace.config.TraceProperties;
import com.cffex.rag.trace.domain.TraceEvent;
import com.cffex.rag.trace.domain.TraceSink;

class AsyncTraceRecorderTest {

    @Test
    void record_writesAsynchronouslyInBatches() {
        TraceProperties properties = enabledProperties();
        properties.getSinks().getJdbc().setBatchSize(2);
        CapturingSink sink = new CapturingSink();
        AsyncTraceRecorder recorder = new AsyncTraceRecorder(properties, List.of(sink));

        recorder.start();
        try {
            recorder.record(event("event.one"));
            assertThat(sink.events).isEmpty();

            recorder.record(event("event.two"));

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sink.events).hasSize(2));
        } finally {
            recorder.stop();
        }
    }

    @Test
    void record_dropsWhenQueueIsFullWithoutBlocking() {
        TraceProperties properties = enabledProperties();
        properties.getSinks().getJdbc().setQueueCapacity(1);
        properties.getSinks().getJdbc().setBatchSize(100);
        properties.getSinks().getJdbc().setFlushIntervalMs(30000);
        properties.getSinks().getJdbc().setOverflowPolicy(TraceProperties.OverflowPolicy.DROP_NEWEST);
        BlockingSink sink = new BlockingSink();
        AsyncTraceRecorder recorder = new AsyncTraceRecorder(properties, List.of(sink));

        recorder.start();
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                recorder.record(event("event." + i));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(200);
        } finally {
            recorder.stop();
        }
    }

    private static TraceProperties enabledProperties() {
        TraceProperties properties = new TraceProperties();
        properties.setEnabled(true);
        properties.getSinks().getJdbc().setEnabled(true);
        properties.getSinks().getJdbc().setQueueCapacity(100);
        properties.getSinks().getJdbc().setFlushIntervalMs(1000);
        return properties;
    }

    private static TraceEvent event(String eventName) {
        return new TraceEvent(
                "trace-1",
                "request-1",
                "test",
                "stage",
                eventName,
                Instant.now(),
                Map.of("k", "v")
        );
    }

    private static final class CapturingSink implements TraceSink {
        private final List<TraceEvent> events = new ArrayList<>();

        @Override
        public synchronized void write(List<TraceEvent> events) {
            this.events.addAll(events);
        }
    }

    private static final class BlockingSink implements TraceSink {
        @Override
        public void write(List<TraceEvent> events) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
