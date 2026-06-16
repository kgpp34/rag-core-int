package com.cffex.rag.trace.infrastructure.jdbc;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

import com.cffex.rag.trace.domain.TraceEvent;
import com.cffex.rag.trace.domain.TraceSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JdbcTraceSink implements TraceSink {

    private static final String INSERT_SQL = """
            INSERT INTO t_rag_trace_event
            (trace_id, request_id, source, stage, event_name, event_time, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcTraceSink(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void write(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
            ps.setString(1, event.traceId());
            ps.setString(2, event.requestId());
            ps.setString(3, event.source());
            ps.setString(4, event.stage());
            ps.setString(5, event.eventName());
            ps.setTimestamp(6, Timestamp.from(event.occurredAt()));
            ps.setString(7, toJson(event.payload()));
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getClass().getSimpleName() + "\"}";
        }
    }
}
