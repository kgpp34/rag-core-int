package com.cffex.rag.common.domain.metadata;

import java.util.Objects;

public record DocumentDatasetMapping(
        String documentId,
        String knowledgeBaseId
) {
    public DocumentDatasetMapping {
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
    }
}
