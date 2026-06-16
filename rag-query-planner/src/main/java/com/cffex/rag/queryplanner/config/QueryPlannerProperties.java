package com.cffex.rag.queryplanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.cffex.rag.common.domain.query.PlanType;

@ConfigurationProperties(prefix = "query-planner")
public class QueryPlannerProperties {

    private PlanType defaultPlanType = PlanType.STANDARD_RETRIEVAL;
    private final Defaults defaults = new Defaults();

    public PlanType getDefaultPlanType() {
        return defaultPlanType;
    }

    public void setDefaultPlanType(PlanType defaultPlanType) {
        this.defaultPlanType = defaultPlanType == null ? PlanType.STANDARD_RETRIEVAL : defaultPlanType;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public static class Defaults {

        private String embeddingModelId;
        private String rerankModelId;
        private int topK = 5;
        private int candidateK = 20;

        public String getEmbeddingModelId() {
            return embeddingModelId;
        }

        public void setEmbeddingModelId(String embeddingModelId) {
            this.embeddingModelId = normalize(embeddingModelId);
        }

        public String getRerankModelId() {
            return rerankModelId;
        }

        public void setRerankModelId(String rerankModelId) {
            this.rerankModelId = normalize(rerankModelId);
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getCandidateK() {
            return candidateK;
        }

        public void setCandidateK(int candidateK) {
            this.candidateK = candidateK;
        }

        private String normalize(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

}
