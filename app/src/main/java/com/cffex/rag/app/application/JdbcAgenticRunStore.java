package com.cffex.rag.app.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
class JdbcAgenticRunStore implements AgenticRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcAgenticRunStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(UUID runId, String requestId, String conversationId, String userId, String query) {
        jdbcTemplate.update("""
                INSERT INTO t_rag_agentic_run
                    (run_id, request_id, conversation_id, user_id, status, query, memory_status)
                VALUES (?, ?, ?, ?, 'queued', ?, ?)
                ON CONFLICT (run_id) DO NOTHING
                """, runId, requestId, conversationId, userId, query,
                conversationId == null ? "not_required" : "pending");
    }

    @Override
    public void markRunning(UUID runId) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET status = 'running', updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND status = 'queued'
                """, runId);
    }

    @Override
    public void markCompleted(UUID runId, JsonNode output) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET status = 'completed', output = CAST(? AS jsonb), error = NULL,
                    memory_status = CASE
                        WHEN conversation_id IS NULL THEN 'not_required'
                        WHEN memory_written THEN 'written'
                        WHEN memory_status = 'written' THEN 'written'
                        ELSE 'pending'
                    END,
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, json(output), runId);
    }

    @Override
    public void markFailed(UUID runId, JsonNode error) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET status = 'failed', error = CAST(? AS jsonb),
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, json(error), runId);
    }

    @Override
    public void markCancelled(UUID runId) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET status = 'cancelled', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND status IN ('queued', 'running')
                """, runId);
    }

    @Override
    public boolean claimMemoryWrite(UUID runId, Instant staleBefore) {
        return jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET memory_status = 'writing', memory_attempts = memory_attempts + 1,
                    memory_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND status = 'completed' AND conversation_id IS NOT NULL
                  AND (
                    memory_status IN ('pending', 'failed')
                    OR (memory_status = 'writing' AND updated_at < ?)
                  )
                """, runId, jdbcTimestamp(staleBefore)) == 1;
    }

    @Override
    public void markMemoryWritten(UUID runId) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET memory_written = TRUE, memory_status = 'written', memory_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, runId);
    }

    @Override
    public void markMemoryWriteFailed(UUID runId, String error) {
        jdbcTemplate.update("""
                UPDATE t_rag_agentic_run
                SET memory_written = FALSE, memory_status = 'failed', memory_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, error, runId);
    }

    @Override
    public Optional<AgenticRun> findByRunId(UUID runId) {
        return jdbcTemplate.query("""
                SELECT * FROM t_rag_agentic_run WHERE run_id = ?
                """, this::mapRun, runId).stream().findFirst();
    }

    @Override
    public List<AgenticRun> findByConversationId(String conversationId, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM t_rag_agentic_run
                WHERE conversation_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, this::mapRun, conversationId, limit);
    }

    @Override
    public List<AgenticRun> findRecoverable(Instant staleBefore, int maxMemoryAttempts, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM t_rag_agentic_run
                WHERE (status IN ('queued', 'running') AND updated_at < ?)
                   OR (status = 'completed' AND conversation_id IS NOT NULL
                       AND memory_attempts < ?
                       AND (memory_status IN ('pending', 'failed')
                            OR (memory_status = 'writing' AND updated_at < ?)))
                ORDER BY updated_at ASC
                LIMIT ?
                """, this::mapRun,
                jdbcTimestamp(staleBefore), maxMemoryAttempts, jdbcTimestamp(staleBefore), limit);
    }

    private AgenticRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgenticRun(
                rs.getObject("run_id", UUID.class),
                rs.getString("request_id"),
                rs.getString("conversation_id"),
                rs.getString("user_id"),
                rs.getString("status"),
                rs.getString("query"),
                parseJson(rs.getString("output")),
                parseJson(rs.getString("error")),
                rs.getString("memory_status"),
                rs.getInt("memory_attempts"),
                rs.getString("memory_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
        );
    }

    private JsonNode parseJson(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new SQLException("cannot parse agentic run payload", ex);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.nullNode() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("cannot serialize agentic run payload", ex);
        }
    }

    private static OffsetDateTime jdbcTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
