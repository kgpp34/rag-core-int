package com.cffex.rag.metadatacacher.infrastructure.persistence;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 术语表独立初始化器，不再依赖 Spring AI Chat Memory 的 schema 初始化开关。
 *
 * <p>初始化失败时让应用启动失败，避免配置显示已启用、运行时却长期使用空快照。
 */
@Component
@ConditionalOnProperty(name = "rag.terminology-replacement.enabled", havingValue = "true")
@ConditionalOnProperty(
        name = "rag.terminology-replacement.initialize-schema",
        havingValue = "true",
        matchIfMissing = true
)
public class TerminologySchemaInitializer implements InitializingBean {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS t_kb_terminology (
                id BIGSERIAL PRIMARY KEY,
                scope VARCHAR(16) NOT NULL,
                kb_id VARCHAR(64),
                term TEXT NOT NULL,
                "desc" JSONB NOT NULL DEFAULT '[]'::jsonb
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public TerminologySchemaInitializer(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kb_terminology_scope ON t_kb_terminology(scope)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kb_terminology_kb_id ON t_kb_terminology(kb_id)");
    }
}
