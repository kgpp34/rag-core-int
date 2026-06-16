package com.cffex.rag.retrievalengine.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 候选块领域对象，只表达身份、内容与元数据，不承载阶段分值。
 */
public record Candidate(
        String chunkId,
        String documentId,
        String knowledgeBaseId,
        String content,
        Map<String, Object> metadata
) {

    public Candidate {
        chunkId = Objects.requireNonNull(chunkId, "chunkId must not be null");
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }

    public String candidateKey() {
        return knowledgeBaseId + ":" + chunkId;
    }

    public String docId() {
        Object docId = metadata.get("doc_id");
        if (docId != null && !docId.toString().isBlank()) {
            return docId.toString();
        }
        Object nestedMetadata = metadata.get("metadata");
        if (nestedMetadata instanceof Map<?, ?> nested) {
            Object nestedDocId = nested.get("doc_id");
            if (nestedDocId != null && !nestedDocId.toString().isBlank()) {
                return nestedDocId.toString();
            }
        }
        return candidateKey();
    }

    public Candidate withMetadata(Map<String, Object> newMetadata) {
        return new Candidate(chunkId, documentId, knowledgeBaseId, content, newMetadata);
    }
}
