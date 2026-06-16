package com.cffex.rag.retrievalengine.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接配置（绑定 {@code milvus.*}）。
 *
 * <pre>
 * milvus:
 *   uri:      http://127.0.0.1:19530
 *   username: root
 *   password: Milvus
 * </pre>
 */
@ConfigurationProperties(prefix = "milvus")
public record MilvusProperties(
        String uri,
        String username,
        String password,
        String databaseName,
        String contentFieldName,
        String metadataFieldName,
        String embeddingFieldName
) {

    public MilvusProperties {
        Objects.requireNonNull(uri, "milvus.uri must not be null");
        username = username != null ? username : "root";
        password = password != null ? password : "";
        databaseName = databaseName != null && !databaseName.isBlank() ? databaseName : "default";
        contentFieldName = contentFieldName != null && !contentFieldName.isBlank() ? contentFieldName : "page_content";
        metadataFieldName = metadataFieldName != null && !metadataFieldName.isBlank() ? metadataFieldName : "metadata";
        embeddingFieldName = embeddingFieldName != null && !embeddingFieldName.isBlank() ? embeddingFieldName : "vector";
    }
}
