package com.cffex.rag.retrievalengine.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Kingbase 向量库连接装配。
 *
 * <p>只在配置了 {@code kingbase.url} 时生效，未配置时不会新增任何 DataSource/JdbcTemplate
 * 候选 bean，避免影响既有的 {@code @Primary} 数据源与 Spring AI 的 JDBC 自动装配。
 *
 * <p>连接池的 {@code connectionInitSql} 里设置 {@code hnsw.ef_search}：KES_Vector 的
 * HNSW 索引默认只返回 40 条候选，candidateK 大于 40 时会被静默截断；放在连接初始化里
 * 可以避免每条查询都发一次 {@code SET}，也不会把会话级 GUC 泄漏给池里的其他请求。
 */
@Configuration
@ConditionalOnProperty(prefix = "kingbase", name = "url")
public class KingbaseVectorStoreConfiguration {

    @Bean(name = "kingbaseDataSource", destroyMethod = "close")
    public DataSource kingbaseDataSource(KingbaseProperties properties) {
        if (properties.url() == null || properties.url().isBlank()) {
            throw new IllegalStateException("启用 Kingbase 必须配置 kingbase.url");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("kingbase-vector-store");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setDriverClassName(properties.driverClassName());
        config.setMaximumPoolSize(properties.maxPoolSize());
        // 向量库不可用时不允许阻塞应用启动，只在真正检索时暴露错误
        config.setInitializationFailTimeout(-1);
        if (properties.hnswEfSearch() > 0) {
            config.setConnectionInitSql("SET hnsw.ef_search = " + properties.hnswEfSearch());
        }
        return new HikariDataSource(config);
    }

    @Bean(name = "kingbaseJdbcTemplate")
    public JdbcTemplate kingbaseJdbcTemplate(@Qualifier("kingbaseDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
