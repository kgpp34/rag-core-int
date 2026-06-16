package com.cffex.rag.common.domain.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 高层执行计划：描述一次查询应如何被编排执行。
 *
 * <p>首版仅承载单个或多个 {@link RetrievalPlan}，用于表达 planner 的上层意图；
 * app 层可依据此对象决定执行顺序，retrieval-engine 仍只消费具体的 {@link RetrievalPlan}。
 */
public record ExecutionPlan(
        PlanType planType,
        String query,
        String systemPrompt,
        List<RetrievalPlan> retrievalPlans,
        Map<String, Object> orchestrationOptions
) {
    public ExecutionPlan {
        planType = Objects.requireNonNull(planType, "planType must not be null");
        query = Objects.requireNonNull(query, "query must not be null");
        systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
        retrievalPlans = List.copyOf(Objects.requireNonNull(retrievalPlans, "retrievalPlans must not be null"));
        orchestrationOptions = Map.copyOf(Objects.requireNonNull(orchestrationOptions, "orchestrationOptions must not be null"));
        if (retrievalPlans.isEmpty()) {
            throw new IllegalArgumentException("retrievalPlans must not be empty");
        }
    }

    public RetrievalPlan primaryRetrievalPlan() {
        return retrievalPlans.getFirst();
    }
}
