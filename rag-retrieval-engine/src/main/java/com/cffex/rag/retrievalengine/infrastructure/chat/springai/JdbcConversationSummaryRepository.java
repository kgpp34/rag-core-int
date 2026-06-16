package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConversationSummaryRepository implements ConversationSummaryRepository {

    private static final String TABLE = "t_rag_conversation_summary";

    private final JdbcTemplate jdbcTemplate;

    JdbcConversationSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ConversationSummary> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
                "SELECT conversation_id, summary_content, summarized_message_count FROM " + TABLE
                        + " WHERE conversation_id = ?",
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("conversation_id"),
                        rs.getString("summary_content"),
                        rs.getInt("summarized_message_count")
                ),
                conversationId
        ).stream().findFirst();
    }

    @Override
    public void save(ConversationSummary summary) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO " + TABLE
                        + " (conversation_id, summary_content, summarized_message_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (conversation_id) DO UPDATE "
                        + "SET summary_content = EXCLUDED.summary_content, "
                        + "summarized_message_count = EXCLUDED.summarized_message_count, "
                        + "updated_at = EXCLUDED.updated_at",
                summary.conversationId(), summary.content(), summary.summarizedMessageCount(), now, now
        );
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE conversation_id = ?", conversationId);
    }
}
