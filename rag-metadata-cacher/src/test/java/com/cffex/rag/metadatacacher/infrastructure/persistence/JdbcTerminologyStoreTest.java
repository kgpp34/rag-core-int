package com.cffex.rag.metadatacacher.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.cffex.rag.metadatacacher.domain.terminology.TerminologyRecord;

@ExtendWith(MockitoExtension.class)
class JdbcTerminologyStoreTest {

    @Test
    void loadAll_queriesTerminologyTableAndMapsRows() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getString("scope")).thenReturn("global");
        when(resultSet.getString("kb_id")).thenReturn(null);
        when(resultSet.getString("term")).thenReturn("国债期货");
        when(resultSet.getString("desc")).thenReturn("[\"中金所国债\"]");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        List<TerminologyRecord> records = new JdbcTerminologyStore(jdbcTemplate).loadAll();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("t_kb_terminology"));
        // desc 为 SQL 保留字，必须双引号引用
        assertTrue(sql.contains("\"desc\""));

        assertEquals(1, records.size());
        assertEquals(7L, records.get(0).id());
        assertEquals("global", records.get(0).scope());
        assertEquals(null, records.get(0).kbId());
        assertEquals("国债期货", records.get(0).term());
        assertEquals("[\"中金所国债\"]", records.get(0).descJson());
    }
}
