package com.cffex.rag.common.domain.query;

import java.util.List;
import java.util.Objects;

public record QueryPlanRequest(
        String query,
        List<String> docIds,
        PlanType planType,
        String systemPrompt
) {
    public QueryPlanRequest {
        query = Objects.requireNonNull(query, "query must not be null");
        docIds = List.copyOf(Objects.requireNonNull(docIds, "docIds must not be null"));
        systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
    }

    public QueryPlanRequest(String query, List<String> docIds) {
        this(query, docIds, null, null);
    }
}
