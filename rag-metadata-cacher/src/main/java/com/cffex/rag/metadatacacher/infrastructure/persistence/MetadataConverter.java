package com.cffex.rag.metadatacacher.infrastructure.persistence;

import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 元数据字段转换工具。
 *
 * <p>负责把数据库中的原始字符串编码转换为领域层可直接使用的枚举和值对象字段。
 * 该类仅供持久化适配层内部调用。
 */
final class MetadataConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_ENGINE_ID = "milvus";
    /** 本引擎已经实现过的存储端口；其余类型一律走默认引擎 */
    private static final java.util.Set<String> SUPPORTED_ENGINE_IDS = java.util.Set.of("milvus", "kingbase");

    private MetadataConverter() {
    }

    static ModelType toModelType(String code) {
        if ("rerank".equalsIgnoreCase(code) || "reranking".equalsIgnoreCase(code)) {
            return ModelType.RERANK;
        }
        if ("llm".equalsIgnoreCase(code) || "chat".equalsIgnoreCase(code)
                || "text-generation".equalsIgnoreCase(code)
                || "completion".equalsIgnoreCase(code)) {
            return ModelType.LLM;
        }
        return ModelType.EMBEDDING;
    }

    /**
     * 解析知识库应该用哪个检索引擎。
     *
     * <p>优先级：{@code datasets.index_struct.type} &gt; 调用方给的本部署默认值 &gt; milvus。
     * 认得的只有本引擎真正实现过的存储（milvus / kingbase）；其它类型
     * （pgvector / weaviate / ...）视为“本部署未实现”，回落到默认值，
     * 避免 RetrieverRegistry 抛 "No storage port supports engineId=..."。
     * 新增存储引擎时在 {@link #SUPPORTED_ENGINE_IDS} 里加一条即可。
     */
    static String resolveEngineId(String vectorType, String defaultEngineId) {
        String fallback = normalizeEngineId(defaultEngineId);
        if (vectorType == null || vectorType.isBlank()) {
            return fallback;
        }
        String candidate = vectorType.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_ENGINE_IDS.contains(candidate) ? candidate : fallback;
    }

    private static String normalizeEngineId(String engineId) {
        return engineId == null || engineId.isBlank()
                ? DEFAULT_ENGINE_ID
                : engineId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    static RetrievalMode toRetrievalMode(String code) {
        if (code == null || code.isBlank()) {
            return RetrievalMode.SEMANTIC;
        }
        String normalized = code.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("hybrid")) {
            return RetrievalMode.HYBRID;
        }
        if (normalized.contains("full_text")
                || normalized.contains("full-text")
                || normalized.contains("full text")
                || normalized.contains("keyword")) {
            return RetrievalMode.FULL_TEXT;
        }
        return RetrievalMode.SEMANTIC;
    }

    static String extractUploadFileId(String dataSourceInfo) {
        if (dataSourceInfo == null || dataSourceInfo.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(dataSourceInfo);
            JsonNode uploadFileId = node.get("upload_file_id");
            return uploadFileId == null || uploadFileId.isNull() || uploadFileId.asText().isBlank()
                    ? null
                    : uploadFileId.asText();
        } catch (Exception ex) {
            return null;
        }
    }
}
