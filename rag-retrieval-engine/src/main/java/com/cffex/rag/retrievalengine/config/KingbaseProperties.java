package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kingbase（人大金仓）向量库连接与检索配置（绑定 {@code kingbase.*}）。
 *
 * <pre>
 * kingbase:
 *   url: jdbc:postgresql://127.0.0.1:54321/dify
 *   username: system
 *   password: 123456
 *   driver-class-name: org.postgresql.Driver
 *   table-prefix: embedding_
 *   fts-config: simple
 *   hnsw-ef-search: 100
 *   max-pool-size: 10
 * </pre>
 *
 * <p>KingbaseES 的 KES_Vector 扩展兼容 pgvector，因此这里复用 PostgreSQL JDBC 驱动，
 * 也可以改成官方驱动 {@code com.kingbase8.Driver}。
 *
 * <p>构造器不做必填校验：未配置 {@code kingbase.url} 时整个配置可以缺省，
 * URL 的校验放在真正创建连接池的 {@code KingbaseVectorStoreConfiguration} 里。
 */
@ConfigurationProperties(prefix = "kingbase")
public record KingbaseProperties(
        String url,
        String username,
        String password,
        String driverClassName,
        String tablePrefix,
        String ftsConfig,
        int hnswEfSearch,
        int maxPoolSize
) {

    public KingbaseProperties {
        username = username != null && !username.isBlank() ? username : "system";
        password = password != null ? password : "";
        driverClassName = driverClassName != null && !driverClassName.isBlank()
                ? driverClassName
                : "org.postgresql.Driver";
        tablePrefix = tablePrefix != null && !tablePrefix.isBlank() ? tablePrefix : "embedding_";
        // 显式配置为空串表示关闭全文检索，只有缺省（null）才回落到 simple
        ftsConfig = ftsConfig != null ? ftsConfig.trim() : "simple";
        // KES_Vector 的 hnsw.ef_search 默认 40，小于它时 HNSW 最多只返回 40 条
        hnswEfSearch = hnswEfSearch > 0 ? hnswEfSearch : 100;
        maxPoolSize = maxPoolSize > 0 ? maxPoolSize : 10;
    }
}
