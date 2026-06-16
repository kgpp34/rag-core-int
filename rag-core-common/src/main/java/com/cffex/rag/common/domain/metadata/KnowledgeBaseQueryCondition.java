package com.cffex.rag.common.domain.metadata;

import java.util.List;
import java.util.Objects;

public record KnowledgeBaseQueryCondition(
        List<String> documentIds
) {
    public KnowledgeBaseQueryCondition {
        documentIds = normalizeList(documentIds, "documentIds");
    }

    public static KnowledgeBaseQueryCondition all() {
        return new KnowledgeBaseQueryCondition(List.of());
    }

    private static List<String> normalizeList(List<String> values, String fieldName) {
        return List.copyOf(Objects.requireNonNull(values, fieldName + " must not be null").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList());
    }
}
