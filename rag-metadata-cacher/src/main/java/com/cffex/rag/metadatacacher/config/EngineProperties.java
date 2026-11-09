package com.cffex.rag.metadatacacher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索存储引擎的全局默认值（绑定 {@code rag.engine.*}）。
 *
 * <pre>
 * rag:
 *   engine:
 *     vector:    kingbase   # 默认向量存储
 *     full-text: kingbase   # 默认全文存储
 * </pre>
 *
 * <p>优先级：<b>知识库自身的 {@code datasets.index_struct.type} &gt; 这里的默认值 &gt; milvus</b>。
 *
 * <p>之所以不能只保留全局配置：同一个 Dify 实例里，历史知识库的向量在 Milvus、
 * 新迁的向量在 Kingbase，必须按知识库分别决定；全局配置只负责“元数据没说的时候用谁”。
 */
@ConfigurationProperties(prefix = "rag.engine")
public record EngineProperties(String vector, String fullText) {

    public static final String DEFAULT_ENGINE_ID = "milvus";

    public EngineProperties {
        vector = normalize(vector);
        fullText = normalize(fullText);
    }

    private static String normalize(String engineId) {
        return engineId == null || engineId.isBlank()
                ? DEFAULT_ENGINE_ID
                : engineId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
