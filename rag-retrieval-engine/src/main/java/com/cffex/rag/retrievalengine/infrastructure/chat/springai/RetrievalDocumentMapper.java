package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 检索候选与 Spring AI Document 之间的映射工具。
 */
public final class RetrievalDocumentMapper {

    public static final String DOCUMENT_ID_METADATA_KEY = "document_id";
    public static final String KNOWLEDGE_BASE_ID_METADATA_KEY = "knowledge_base_id";
    public static final String VECTOR_SCORE_METADATA_KEY = "vector_score";
    public static final String SPARSE_SCORE_METADATA_KEY = "sparse_score";

    private RetrievalDocumentMapper() {
    }

    public static Document toDocument(RetrievalCandidate candidate) {
        // 检索主链路内部最终要和 Spring AI Document 对齐，
        // 因此这里把业务字段显式塞回 metadata，避免后面再丢信息。
        Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
        metadata.put(DOCUMENT_ID_METADATA_KEY, candidate.documentId());
        metadata.put(KNOWLEDGE_BASE_ID_METADATA_KEY, candidate.knowledgeBaseId());
        metadata.put(VECTOR_SCORE_METADATA_KEY, candidate.vectorScore());
        if (candidate.sparseScore() != null) {
            metadata.put(SPARSE_SCORE_METADATA_KEY, candidate.sparseScore());
        }
        return new Document(candidate.chunkId(), candidate.content(), metadata);
    }

    public static RetrievalCandidate toCandidate(Document document) {
        Objects.requireNonNull(document, "document must not be null");

        // 这里做的是“反向映射”：把 Spring AI Document 还原成内部统一候选对象，
        // 这样后面的元数据补全、排序、rerank 都继续复用原有领域模型。
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        return new RetrievalCandidate(
                Objects.toString(document.getId(), ""),
                stringMetadata(metadata, DOCUMENT_ID_METADATA_KEY),
                stringMetadata(metadata, KNOWLEDGE_BASE_ID_METADATA_KEY),
                Objects.toString(document.getText(), ""),
                doubleMetadata(metadata, VECTOR_SCORE_METADATA_KEY),
                nullableDoubleMetadata(metadata, SPARSE_SCORE_METADATA_KEY),
                metadata
        );
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : Objects.toString(value, "");
    }

    private static double doubleMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    private static Double nullableDoubleMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
