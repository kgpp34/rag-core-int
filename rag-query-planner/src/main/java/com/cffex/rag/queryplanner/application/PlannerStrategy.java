package com.cffex.rag.queryplanner.application;

import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;

interface PlannerStrategy {

    PlanType planType();

    ExecutionPlan plan(QueryPlanRequest request);

    default ExecutionPlan plan(RetrievalContext context) {
        throw new UnsupportedOperationException("direct retrieval context is not supported by planner: " + planType());
    }
}
