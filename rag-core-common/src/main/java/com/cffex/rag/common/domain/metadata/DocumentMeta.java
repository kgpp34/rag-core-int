package com.cffex.rag.common.domain.metadata;

import java.util.Objects;

public record DocumentMeta(
        String documentId,
        String knowledgeBaseId,
        String name,
        String uploadFileId,
        String uploadFileKey,
        String sourceType,
        String referenceUrl
) {
    public DocumentMeta(
            String documentId,
            String knowledgeBaseId,
            String name,
            String uploadFileId,
            String uploadFileKey
    ) {
        this(documentId, knowledgeBaseId, name, uploadFileId, uploadFileKey, null, null);
    }

    public DocumentMeta {
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        name = normalize(name);
        uploadFileId = normalize(uploadFileId);
        uploadFileKey = normalize(uploadFileKey);
        sourceType = normalize(sourceType);
        referenceUrl = normalize(referenceUrl);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
