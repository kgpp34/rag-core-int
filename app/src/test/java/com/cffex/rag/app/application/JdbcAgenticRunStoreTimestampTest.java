package com.cffex.rag.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class JdbcAgenticRunStoreTimestampTest {

    @Test
    void findRecoverableUsesJdbc42TimestampValuesInsteadOfInstant() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(java.util.List.of());
        JdbcAgenticRunStore store = new JdbcAgenticRunStore(jdbcTemplate, new ObjectMapper());
        Instant staleBefore = Instant.parse("2026-08-26T03:00:00Z");

        store.findRecoverable(staleBefore, 5, 50);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                arguments.capture());
        assertThat(arguments.getValue())
                .containsExactly(
                        OffsetDateTime.parse("2026-08-26T03:00:00Z"),
                        5,
                        OffsetDateTime.parse("2026-08-26T03:00:00Z"),
                        50
                );
    }
}
