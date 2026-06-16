package com.cffex.rag.common.domain.metadata;

import java.util.Objects;

public record DomainKnowledgeBaseMapping(
        BusinessDomain businessDomain,
        String knowledgeBaseId
) {
    public DomainKnowledgeBaseMapping {
        businessDomain = Objects.requireNonNull(businessDomain, "businessDomain must not be null");
        knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
    }
}
