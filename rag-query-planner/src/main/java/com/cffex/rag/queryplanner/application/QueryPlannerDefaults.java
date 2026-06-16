package com.cffex.rag.queryplanner.application;

final class QueryPlannerDefaults {

    static final int TOP_K = 5;
    static final int CANDIDATE_K = 20;
    static final double VECTOR_WEIGHT = 0.7;
    static final double KEYWORD_WEIGHT = 0.3;

    private QueryPlannerDefaults() {
    }
}
