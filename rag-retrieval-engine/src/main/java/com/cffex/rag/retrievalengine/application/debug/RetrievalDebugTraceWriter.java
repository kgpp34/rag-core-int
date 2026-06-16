package com.cffex.rag.retrievalengine.application.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.config.RetrievalDebugTraceProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.trace.application.NoOpTraceRecorder;
import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;
import com.cffex.rag.trace.domain.TraceEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class RetrievalDebugTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(RetrievalDebugTraceWriter.class);
    private static final String SOURCE = "rag-core-int";
    private static final Set<String> SUMMARY_TRACE_EVENTS = Set.of();
    private static final Set<String> DETAIL_TRACE_EVENTS = Set.of(
            "retrieval.request",
            "query.preprocess",
            "embedding.query",
            "embedding.skipped",
            "recall.completed",
            "metadata.enrich.completed",
            "global.merge.before_rerank",
            "global.rerank.skipped",
            "global.rerank.request",
            "global.rerank.response",
            "global.final",
            "retrieval.execution.completed",
            "kb.recall.started",
            "kb.recall.completed",
            "kb.rerank.skipped",
            "kb.rerank.request",
            "kb.rerank.response",
            "kb.final"
    );

    private final RetrievalDebugTraceProperties properties;
    private final DebugTraceContext traceContext;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traceRecorder;
    private final TraceProperties traceProperties;
    private final ConcurrentMap<Path, Object> fileLocks = new ConcurrentHashMap<>();

    public RetrievalDebugTraceWriter(
            RetrievalDebugTraceProperties properties,
            DebugTraceContext traceContext,
            ObjectMapper objectMapper
    ) {
        this(properties, traceContext, objectMapper, new NoOpTraceRecorder(), new TraceProperties());
    }

    @Autowired
    public RetrievalDebugTraceWriter(
            RetrievalDebugTraceProperties properties,
            DebugTraceContext traceContext,
            ObjectMapper objectMapper,
            TraceRecorder traceRecorder,
            TraceProperties traceProperties
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
        this.traceProperties = Objects.requireNonNull(traceProperties, "traceProperties must not be null");
    }

    public boolean enabled() {
        return properties.enabled() || traceRecorder.enabled();
    }

    public void record(String event, Map<String, ?> payload) {
        if (!enabled()) {
            return;
        }
        String traceId = traceContext.currentTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = "unknown";
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("source", SOURCE);
        line.put("traceId", traceId);
        line.put("event", event);
        line.put("timestamp", Instant.now().toString());
        line.put("payload", payload == null ? Map.of() : payload);
        if (traceRecorder.enabled() && shouldPersistTraceEvent(event)) {
            traceRecorder.record(new TraceEvent(
                    traceId,
                    MDC.get("retrievalRequestId"),
                    source(event),
                    stage(event),
                    event,
                    Instant.now(),
                    payload == null ? Map.of() : copyPayload(payload)
            ));
        }
        if (properties.enabled()) {
            writeLine(traceId, line);
        }
    }

    private boolean shouldPersistTraceEvent(String event) {
        if (event == null || event.isBlank()) {
            return false;
        }
        if (event.endsWith(".failed") || event.contains(".failed")) {
            return true;
        }
        return switch (traceProperties.getMode()) {
            case OFF -> false;
            case SUMMARY -> SUMMARY_TRACE_EVENTS.contains(event);
            case DETAIL -> DETAIL_TRACE_EVENTS.contains(event);
            case DEBUG -> true;
        };
    }

    public Map<String, Object> embeddingSummary(float[] vector) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (vector == null) {
            summary.put("dim", 0);
            return summary;
        }
        summary.put("dim", vector.length);
        summary.put("l2Norm", l2Norm(vector));
        summary.put("sha256", sha256(vector));
        summary.put("first8", first(vector, 8));
        if (properties.includeEmbeddingVector()) {
            summary.put("vector", toList(vector));
        }
        return summary;
    }

    public List<Map<String, Object>> candidates(List<RetrievalCandidate> candidates) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = candidateLimit(candidates.size());
        for (int i = 0; i < limit; i++) {
            rows.add(candidate(i, candidates.get(i)));
        }
        return rows;
    }

    public List<Map<String, Object>> chunks(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = candidateLimit(chunks.size());
        for (int i = 0; i < limit; i++) {
            rows.add(chunk(i, chunks.get(i)));
        }
        return rows;
    }

    public Map<String, Object> candidate(int index, RetrievalCandidate candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("kbId", candidate.knowledgeBaseId());
        row.put("chunkId", candidate.chunkId());
        row.put("documentId", candidate.documentId());
        row.put("docId", metadataValue(candidate, "doc_id"));
        row.put("vectorScore", candidate.vectorScore());
        row.put("sparseScore", candidate.sparseScore());
        row.put("textHash", sha256(candidate.content()));
        row.put("textPreview", preview(candidate.content()));
        row.put("metadata", candidate.metadata());
        return row;
    }

    public Map<String, Object> chunk(int index, RetrievedChunk chunk) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("kbId", chunk.knowledgeBaseId());
        row.put("chunkId", chunk.chunkId());
        row.put("documentId", chunk.documentId());
        row.put("docId", metadataValue(chunk.metadata(), "doc_id"));
        row.put("vectorScore", chunk.vectorScore());
        row.put("sparseScore", chunk.sparseScore());
        row.put("rankingScore", chunk.rankingScore());
        row.put("score", chunk.rankingScore());
        row.put("textHash", sha256(chunk.content()));
        row.put("textPreview", preview(chunk.content()));
        row.put("metadata", chunk.metadata());
        return row;
    }

    public String preview(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int maxLength = properties.textPreviewLength();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private void writeLine(String traceId, Map<String, Object> line) {
        String event = Objects.toString(line.get("event"), "");
        byte[] bytes;
        try {
            bytes = (objectMapper.writeValueAsString(line) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException ex) {
            log.warn("检索调试 trace 序列化失败 | event={}, error={}", event, ex.getMessage());
            return;
        }

        Path file = Path.of(properties.outputDir(), traceId, "events.jsonl");
        Object lock = fileLocks.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(
                        file,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException ex) {
                log.warn("检索调试 trace 写入失败 | event={}, error={}", event, ex.getMessage());
            }
        }
    }

    private static String source(String event) {
        if (event != null && event.startsWith("milvus.")) {
            return "milvus";
        }
        return "retrieval-engine";
    }

    private static String stage(String event) {
        if (event == null) {
            return "retrieval";
        }
        if (event.startsWith("embedding.")) {
            return "embedding";
        }
        if (event.startsWith("milvus.") || event.startsWith("kb.") || event.startsWith("recall.")) {
            return "recall";
        }
        if (event.contains("rerank")) {
            return "rerank";
        }
        return "retrieval";
    }

    private static Map<String, Object> copyPayload(Map<String, ?> payload) {
        Map<String, Object> copy = new LinkedHashMap<>();
        payload.forEach(copy::put);
        return copy;
    }

    private int candidateLimit(int size) {
        if (properties.enabled()) {
            return size;
        }
        return Math.min(size, traceProperties.getMaxCandidatesPerEvent());
    }

    private static Object metadataValue(RetrievalCandidate candidate, String key) {
        return metadataValue(candidate.metadata(), key);
    }

    private static Object metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value != null) {
            return value;
        }
        Object nestedMetadata = metadata.get("metadata");
        if (nestedMetadata instanceof Map<?, ?> nested) {
            return nested.get(key);
        }
        return null;
    }

    private static double l2Norm(float[] vector) {
        double sum = 0.0d;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private static List<Float> first(float[] vector, int limit) {
        int length = Math.min(vector.length, limit);
        List<Float> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            values.add(vector[i]);
        }
        return values;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static String sha256(float[] vector) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (float value : vector) {
                int bits = Float.floatToIntBits(value);
                digest.update((byte) (bits >>> 24));
                digest.update((byte) (bits >>> 16));
                digest.update((byte) (bits >>> 8));
                digest.update((byte) bits);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
