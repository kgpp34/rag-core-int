package com.cffex.rag.trace.infrastructure.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

import com.cffex.rag.trace.domain.TraceEvent;
import com.cffex.rag.trace.domain.TraceSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonlTraceSink implements TraceSink {

    private final Path outputDir;
    private final ObjectMapper objectMapper;

    public JsonlTraceSink(String outputDir, ObjectMapper objectMapper) {
        this.outputDir = Path.of(Objects.requireNonNull(outputDir, "outputDir must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void write(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (TraceEvent event : events) {
            writeOne(event);
        }
    }

    private void writeOne(TraceEvent event) {
        Path file = outputDir.resolve(event.traceId()).resolve("events.jsonl");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Trace event serialization failed", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Trace event file write failed", ex);
        }
    }
}
