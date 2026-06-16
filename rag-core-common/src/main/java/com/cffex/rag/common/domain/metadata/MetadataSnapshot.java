package com.cffex.rag.common.domain.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record MetadataSnapshot(
        long version,
        Instant refreshedAt,
        Map<String, KnowledgeBaseMeta> knowledgeBasesById,
        Map<String, ModelMeta> modelsById,
        Map<String, String> documentToKnowledgeBase,
        Map<String, DocumentMeta> documentMetasById,
        Map<BusinessDomain, List<String>> domainToKnowledgeBases
) {
    public MetadataSnapshot {
        refreshedAt = Objects.requireNonNull(refreshedAt, "refreshedAt must not be null");
        knowledgeBasesById = Map.copyOf(Objects.requireNonNull(knowledgeBasesById, "knowledgeBasesById must not be null"));
        modelsById = Map.copyOf(Objects.requireNonNull(modelsById, "modelsById must not be null"));
        documentToKnowledgeBase = Map.copyOf(Objects.requireNonNull(documentToKnowledgeBase, "documentToKnowledgeBase must not be null"));
        documentMetasById = Map.copyOf(Objects.requireNonNull(documentMetasById, "documentMetasById must not be null"));
        domainToKnowledgeBases = Objects.requireNonNull(domainToKnowledgeBases, "domainToKnowledgeBases must not be null")
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
