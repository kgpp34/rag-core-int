package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.config.MilvusProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.milvus.v2.service.vector.response.SearchResp;

/**
 * Milvus 检索共享支持：统一过滤表达式与响应映射。
 */
@Component
public class MilvusSearchSupport {

    private static final String DOCUMENT_ID_METADATA_KEY = "document_id";
    private static final String CHUNK_ID_METADATA_KEY = "doc_id";
    private static final String KNOWLEDGE_BASE_ID_METADATA_KEY = "knowledge_base_id";

    private final MilvusProperties properties;
    private final ObjectMapper objectMapper;

    public MilvusSearchSupport(MilvusProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public List<String> outputFields() {
        return List.of(properties.contentFieldName(), properties.metadataFieldName());
    }

    public String buildDocIdFilter(List<String> docIds) {
        String quotedIds = docIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(", "));
        return "metadata[\"document_id\"] in [" + quotedIds + "]";
    }

    public List<RetrievalCandidate> toDenseCandidates(SearchResp resp, String knowledgeBaseId) {
        return toRetrievalCandidates(resp, knowledgeBaseId, true);
    }

    public List<RetrievalCandidate> toSparseCandidates(SearchResp resp, String knowledgeBaseId) {
        return toRetrievalCandidates(resp, knowledgeBaseId, false);
    }

    private List<RetrievalCandidate> toRetrievalCandidates(
            SearchResp resp,
            String knowledgeBaseId,
            boolean dense
    ) {
        return resp.getSearchResults().stream()
                .flatMap(List::stream)
                .map(result -> toCandidate(result, knowledgeBaseId, dense))
                .toList();
    }

    private RetrievalCandidate toCandidate(
            SearchResp.SearchResult result,
            String knowledgeBaseId,
            boolean dense
    ) {
        Map<String, Object> rawEntity = Objects.requireNonNullElse(result.getEntity(), Map.of());
        Map<String, Object> entity = sanitizeEntity(rawEntity);
        entity.putIfAbsent(KNOWLEDGE_BASE_ID_METADATA_KEY, knowledgeBaseId);
        // dify 入库时把分段索引节点 ID 写在 metadata JSON 的 doc_id 里；提到顶层，
        // 供引用构建时按 index_node_id 反查分段 position。Milvus 主键是 auto_id 自增数，无业务含义。
        String indexNodeId = extractDocumentId(entity.get(properties.metadataFieldName()), CHUNK_ID_METADATA_KEY);
        if (!indexNodeId.isBlank()) {
            entity.putIfAbsent(CHUNK_ID_METADATA_KEY, indexNodeId);
        }
        double score = Objects.requireNonNullElse(result.getScore(), 0.0f);
        return new RetrievalCandidate(
                Objects.toString(result.getId(), ""),
                extractDocumentId(entity.get(properties.metadataFieldName()), DOCUMENT_ID_METADATA_KEY),
                knowledgeBaseId,
                Objects.toString(entity.get(properties.contentFieldName()), ""),
                dense ? score : 0.0d,
                dense ? null : score,
                entity
        );
    }

    private Map<String, Object> sanitizeEntity(Map<String, Object> raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>(raw.size());
        raw.forEach((key, value) -> sanitized.put(key, sanitizeValue(value)));
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> converted = new LinkedHashMap<>(map.size());
            map.forEach((k, v) -> converted.put(String.valueOf(k), sanitizeValue(v)));
            return converted;
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(this::sanitizeValue).toList();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String className = value.getClass().getName();
        if (className.startsWith("com.google.gson.") || className.startsWith("org.json.")) {
            return deepConvertViaJson(value);
        }
        return value;
    }

    private Object deepConvertViaJson(Object gsonValue) {
        try {
            JsonNode node = objectMapper.readTree(gsonValue.toString());
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception ex) {
            return gsonValue.toString();
        }
    }

    private String extractDocumentId(Object metadataObj, String key) {
        if (metadataObj == null) {
            return "";
        }
        if (metadataObj instanceof Map) {
            Object value = ((Map<?, ?>) metadataObj).get(key);
            return value != null ? Objects.toString(value, "") : "";
        }
        try {
            JsonNode node = objectMapper.readTree(metadataObj.toString());
            JsonNode value = node.get(key);
            return value != null ? value.asText("") : "";
        } catch (JsonProcessingException ex) {
            return "";
        }
    }
}
