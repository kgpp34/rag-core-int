package com.cffex.rag.queryplanner.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;
import com.cffex.rag.queryplanner.config.QueryPlannerProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultQueryPlannerFacadeTest {

    @Test
    void plan_dispatchesByRequestedPlanType() {
        RecordingPlanner standardPlanner = new RecordingPlanner(PlanType.STANDARD_RETRIEVAL, samplePlan("standard"));
        RecordingPlanner agenticPlanner = new RecordingPlanner(PlanType.AGENTIC_RAG, samplePlan("agentic"));
        DefaultQueryPlannerFacade facade = new DefaultQueryPlannerFacade(
                List.of(standardPlanner, agenticPlanner),
                createProperties()
        );

        ExecutionPlan plan = facade.plan(new QueryPlanRequest("hello", List.of("doc-1"), PlanType.AGENTIC_RAG, null));

        assertEquals("agentic", plan.query());
        assertEquals(1, agenticPlanner.queryPlanCallCount);
        assertEquals(0, standardPlanner.queryPlanCallCount);
    }

    @Test
    void plan_usesConfiguredDefaultPlanTypeWhenRequestMissing() {
        QueryPlannerProperties properties = createProperties();
        properties.setDefaultPlanType(PlanType.STANDARD_RETRIEVAL);
        RecordingPlanner standardPlanner = new RecordingPlanner(PlanType.STANDARD_RETRIEVAL, samplePlan("standard"));
        DefaultQueryPlannerFacade facade = new DefaultQueryPlannerFacade(List.of(standardPlanner), properties);

        ExecutionPlan plan = facade.plan(new QueryPlanRequest("hello", List.of("doc-1")));

        assertEquals("standard", plan.query());
        assertEquals(1, standardPlanner.queryPlanCallCount);
    }

    @Test
    void plan_failsWhenUnsupportedPlanTypeRequested() {
        DefaultQueryPlannerFacade facade = new DefaultQueryPlannerFacade(
                List.of(new RecordingPlanner(PlanType.STANDARD_RETRIEVAL, samplePlan("standard"))),
                createProperties()
        );

        assertThrows(UnsupportedOperationException.class, () -> facade.plan(
                new QueryPlanRequest("hello", List.of(), PlanType.AGENTIC_RAG, null)));
    }

    @Test
    void plan_contextDelegatesToStandardRetrievalPlanner() {
        RecordingPlanner standardPlanner = new RecordingPlanner(PlanType.STANDARD_RETRIEVAL, samplePlan("context"));
        DefaultQueryPlannerFacade facade = new DefaultQueryPlannerFacade(List.of(standardPlanner), createProperties());

        ExecutionPlan plan = facade.plan(new RetrievalContext(
                "tenant-1",
                "hello",
                List.of("kb-1"),
                List.of("doc-1"),
                "embed-default",
                null,
                5,
                20,
                Map.of(),
                Map.of()
        ));

        assertEquals("context", plan.query());
        assertEquals(1, standardPlanner.contextPlanCallCount);
    }

    private static RetrievalPlan samplePlan(String query) {
        return new RetrievalPlan(
                query,
                new EmbeddingSpec(new ModelEndpointSpec("http://embed", "token", "embed-model")),
                List.of(),
                RankingSpec.WeightedRankingSpec.equalWeight(),
                5
        );
    }

    private static QueryPlannerProperties createProperties() {
        QueryPlannerProperties properties = new QueryPlannerProperties();
        properties.getDefaults().setEmbeddingModelId("embed-default");
        properties.getDefaults().setRerankModelId("rerank-default");
        properties.getDefaults().setTopK(8);
        properties.getDefaults().setCandidateK(12);
        return properties;
    }

    private static final class RecordingPlanner implements PlannerStrategy {

        private final PlanType planType;
        private final ExecutionPlan planToReturn;
        private int queryPlanCallCount;
        private int contextPlanCallCount;

        private RecordingPlanner(PlanType planType, RetrievalPlan retrievalPlan) {
            this.planType = planType;
            this.planToReturn = new ExecutionPlan(
                    planType,
                    retrievalPlan.query(),
                    null,
                    List.of(retrievalPlan),
                    Map.of()
            );
        }

        @Override
        public PlanType planType() {
            return planType;
        }

        @Override
        public ExecutionPlan plan(QueryPlanRequest request) {
            queryPlanCallCount++;
            return planToReturn;
        }

        @Override
        public ExecutionPlan plan(RetrievalContext context) {
            contextPlanCallCount++;
            return planToReturn;
        }
    }
}
