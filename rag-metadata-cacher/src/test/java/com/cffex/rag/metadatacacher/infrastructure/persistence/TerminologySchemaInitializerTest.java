package com.cffex.rag.metadatacacher.infrastructure.persistence;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TerminologySchemaInitializerTest {

    @Test
    void afterPropertiesSet_createsTableAndIndexes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        new TerminologySchemaInitializer(jdbcTemplate).afterPropertiesSet();

        verify(jdbcTemplate).execute(contains("CREATE TABLE IF NOT EXISTS t_kb_terminology"));
        verify(jdbcTemplate).execute(contains("idx_kb_terminology_scope"));
        verify(jdbcTemplate).execute(contains("idx_kb_terminology_kb_id"));
    }
}
