package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcConversationSummaryRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void save_usesAtomicUpsertAndJdbcTimestamps() {
        JdbcConversationSummaryRepository repository = new JdbcConversationSummaryRepository(jdbcTemplate);

        repository.save(new ConversationSummary("conv-1", "summary", 12));

        verify(jdbcTemplate).update(
                eq("INSERT INTO t_rag_conversation_summary "
                        + "(conversation_id, summary_content, summarized_message_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (conversation_id) DO UPDATE "
                        + "SET summary_content = EXCLUDED.summary_content, "
                        + "summarized_message_count = EXCLUDED.summarized_message_count, "
                        + "updated_at = EXCLUDED.updated_at"),
                eq("conv-1"),
                eq("summary"),
                eq(12),
                any(Timestamp.class),
                any(Timestamp.class)
        );
    }
}
