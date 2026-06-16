package com.cffex.rag.trace.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.cffex.rag.trace.config.TraceProperties;
import com.cffex.rag.trace.domain.TraceEvent;
import com.cffex.rag.trace.domain.TraceSink;

public class AsyncTraceRecorder implements TraceRecorder, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceRecorder.class);

    private final TraceProperties properties;
    private final List<TraceSink> sinks;
    private final BlockingQueue<TraceEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private Thread worker;

    public AsyncTraceRecorder(TraceProperties properties, List<TraceSink> sinks) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks must not be null"));
        int capacity = properties.getSinks().getJdbc().getQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && !sinks.isEmpty();
    }

    @Override
    public void record(TraceEvent event) {
        if (!enabled() || event == null || !sampled(event)) {
            return;
        }
        boolean accepted = offer(event);
        if (accepted) {
            enqueued.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void start() {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runWorker, "rag-trace-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("RAG trace 异步写入启动 | sinks={}, queueCapacity={}, batchSize={}",
                sinks.size(),
                queue.remainingCapacity() + queue.size(),
                properties.getSinks().getJdbc().getBatchSize());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            try {
                currentWorker.join(properties.getSinks().getJdbc().getShutdownTimeoutMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        drainRemaining();
        if (!queue.isEmpty()) {
            log.warn("RAG trace 关闭时仍有未写入事件 | remaining={}", queue.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private boolean offer(TraceEvent event) {
        TraceProperties.Jdbc jdbc = properties.getSinks().getJdbc();
        try {
            if (jdbc.getOfferTimeoutMs() > 0) {
                return queue.offer(event, jdbc.getOfferTimeoutMs(), TimeUnit.MILLISECONDS);
            }
            if (queue.offer(event)) {
                return true;
            }
            return switch (jdbc.getOverflowPolicy()) {
                case DROP_OLDEST -> {
                    queue.poll();
                    yield queue.offer(event);
                }
                case DROP_NEWEST -> false;
                case BLOCK -> queue.offer(event, 10, TimeUnit.MILLISECONDS);
            };
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean sampled(TraceEvent event) {
        double sampleRate = properties.getSampleRate();
        if (sampleRate >= 1.0d) {
            return true;
        }
        if (sampleRate <= 0.0d) {
            return false;
        }
        String key = event.traceId() + ":" + Objects.toString(event.requestId(), "");
        int bucket = Math.floorMod(key.hashCode(), 10000);
        return bucket < sampleRate * 10000;
    }

    private void runWorker() {
        List<TraceEvent> batch = new ArrayList<>(properties.getSinks().getJdbc().getBatchSize());
        long lastFlush = System.nanoTime();
        while (running.get() || !queue.isEmpty()) {
            try {
                TraceEvent event = queue.poll(properties.getSinks().getJdbc().getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                    queue.drainTo(batch, Math.max(0, properties.getSinks().getJdbc().getBatchSize() - batch.size()));
                }
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFlush);
                if (!batch.isEmpty()
                        && (batch.size() >= properties.getSinks().getJdbc().getBatchSize()
                        || elapsedMs >= properties.getSinks().getJdbc().getFlushIntervalMs()
                        || !running.get())) {
                    writeBatch(batch);
                    batch.clear();
                    lastFlush = System.nanoTime();
                }
            } catch (InterruptedException ex) {
                if (!running.get()) {
                    break;
                }
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                log.warn("RAG trace worker 异常 | error={}", ex.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    private void drainRemaining() {
        List<TraceEvent> batch = new ArrayList<>(properties.getSinks().getJdbc().getBatchSize());
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    private void writeBatch(List<TraceEvent> batch) {
        for (TraceSink sink : sinks) {
            try {
                sink.write(batch);
                written.addAndGet(batch.size());
            } catch (RuntimeException ex) {
                writeFailures.incrementAndGet();
                log.warn("RAG trace 写入失败 | sink={}, batchSize={}, error={}",
                        sink.getClass().getSimpleName(),
                        batch.size(),
                        ex.getMessage());
            }
        }
        if (dropped.get() > 0 && (written.get() % 1000) < batch.size()) {
            log.warn("RAG trace 队列发生丢弃 | queueSize={}, enqueued={}, written={}, dropped={}, failures={}",
                    queue.size(), enqueued.get(), written.get(), dropped.get(), writeFailures.get());
        }
    }
}
