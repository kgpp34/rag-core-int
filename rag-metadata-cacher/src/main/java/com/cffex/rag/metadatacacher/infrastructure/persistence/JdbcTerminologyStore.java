package com.cffex.rag.metadatacacher.infrastructure.persistence;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.cffex.rag.metadatacacher.domain.terminology.TerminologyRecord;

/**
 * t_kb_terminology 全量加载器（主库，与 spring_ai 表同库）。
 *
 * <p>{@code desc} 为 SQL 保留字，SQL 中必须双引号引用；desc 列兼容 JSONB/TEXT，
 * 统一按字符串读出后由 {@code TerminologySnapshotFactory} 解析。
 */
@Repository
public class JdbcTerminologyStore {

    private static final String LOAD_SQL = """
            SELECT id, scope, kb_id, term, "desc"
            FROM t_kb_terminology
            ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTerminologyStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TerminologyRecord> loadAll() {
        return jdbcTemplate.query(LOAD_SQL, (rs, rowNum) -> new TerminologyRecord(
                rs.getLong("id"),
                rs.getString("scope"),
                rs.getString("kb_id"),
                rs.getString("term"),
                rs.getString("desc")
        ));
    }
}
