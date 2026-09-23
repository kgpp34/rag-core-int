package com.cffex.rag.queryplanner.application;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.metadata.RetrievalBindingMeta;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalCapability;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.queryplanner.config.QueryPlannerProperties;

@ExtendWith(MockitoExtension.class)
class StandardRetrievalPlannerTest {

    @Mock
    private MetadataQueryService metadataQueryService;

    private final QueryPlannerProperties properties = createProperties();

    private StandardRetrievalPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new StandardRetrievalPlanner(metadataQueryService, properties);
        lenient().when(metadataQueryService.getDocumentMetas(any())).thenAnswer(invocation -> {
            List<String> docIds = invocation.getArgument(0);
            Map<String, DocumentMeta> metas = new java.util.LinkedHashMap<>();
            for (String docId : docIds) {
                metas.put(docId, new DocumentMeta(docId, inferKnowledgeBaseId(docId), docId + ".txt", null, null));
            }
            return metas;
        });
    }

    @Test
    void plan_usesFilteredKnowledgeBasesFromMetadataService() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1", "doc-2")));

        assertEquals(1, plan.retrievalPlans().size());
        assertEquals("kb-1", plan.primaryRetrievalPlan().recallSpecs().get(0).knowledgeBaseId());
        assertEquals("milvus", plan.primaryRetrievalPlan().recallSpecs().get(0).vectorEngineId());
        assertEquals("milvus", plan.primaryRetrievalPlan().recallSpecs().get(0).fullTextEngineId());
        assertEquals(
                "collection-1",
                plan.primaryRetrievalPlan().recallSpecs().get(0).bindingOf(RetrievalCapability.VECTOR).targetName()
        );
    }

    @Test
    void plan_rejectsRequestedModeUnsupportedByKnowledgeBase() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.SEMANTIC, "collection-1", true)
        ));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                planner.plan(new QueryPlanRequest(
                        "hello",
                        List.of("doc-1"),
                        null,
                        null,
                        RetrievalMode.FULL_TEXT
                )));

        org.junit.jupiter.api.Assertions.assertNotNull(exception.getMessage());
    }

    @Test
    void plan_allowsRequestingSubsetOfHybridKnowledgeBaseCapabilities() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest(
                "hello",
                List.of("doc-1"),
                null,
                null,
                RetrievalMode.FULL_TEXT
        ));

        assertEquals(RetrievalMode.FULL_TEXT, plan.primaryRetrievalPlan().recallSpecs().get(0).retrievalMode());
    }

    @Test
    void plan_failsWhenKnowledgeBasesEmpty() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () ->
                planner.plan(new QueryPlanRequest("hello", List.of("doc-1"))));
    }

    @Test
    void plan_usesConfiguredModelIdsAndThresholds() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        15, true, true, 0.45d, 0.8d, 0.2d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1", "doc-2")));

        assertEquals(1, plan.retrievalPlans().size());
        assertEquals("embed-model", plan.primaryRetrievalPlan().embeddingSpec().modelEndpoint().model());
        assertEquals(8, plan.primaryRetrievalPlan().topK());
        assertEquals(15, plan.primaryRetrievalPlan().recallSpecs().get(0).candidateK());
        assertEquals(15, plan.primaryRetrievalPlan().recallSpecs().get(0).topK());
        assertEquals(RetrievalMode.HYBRID, plan.primaryRetrievalPlan().recallSpecs().get(0).retrievalMode());
        assertEquals(true, plan.primaryRetrievalPlan().recallSpecs().get(0).rerankingEnabled());
        assertEquals(true, plan.primaryRetrievalPlan().recallSpecs().get(0).scoreThresholdEnabled());
        assertEquals(0.45d, plan.primaryRetrievalPlan().recallSpecs().get(0).scoreThreshold());
        assertEquals(0.8d, plan.primaryRetrievalPlan().recallSpecs().get(0).vectorWeight());
        assertEquals(0.2d, plan.primaryRetrievalPlan().recallSpecs().get(0).keywordWeight());
        assertEquals(true, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
        assertEquals(
                "rerank-model",
                plan.primaryRetrievalPlan().recallSpecs().get(0).rerankRankingSpec().modelEndpoint().model()
        );
        assertEquals("rerank-model", ((RankingSpec.RerankRankingSpec) plan.primaryRetrievalPlan().rankingSpec())
                .modelEndpoint()
                .model());
    }

    @Test
    void plan_propagatesExplicitEngineIdsFromKnowledgeBaseMeta() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta(
                        "kb-1",
                        RetrievalMode.HYBRID,
                        "collection-1",
                        "milvus",
                        "elasticsearch",
                        true
                )
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1", "doc-2")));

        assertEquals("milvus", plan.primaryRetrievalPlan().recallSpecs().get(0).vectorEngineId());
        assertEquals("elasticsearch", plan.primaryRetrievalPlan().recallSpecs().get(0).fullTextEngineId());
    }

    @Test
    void plan_prefersExplicitBindingsFromKnowledgeBaseMeta() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta(
                        "kb-1",
                        RetrievalMode.HYBRID,
                        "legacy-collection",
                        List.of(
                                new RetrievalBindingMeta(
                                        RetrievalCapability.VECTOR,
                                        "milvus",
                                        "kb-1-vector-collection",
                                        true
                                ),
                                new RetrievalBindingMeta(
                                        RetrievalCapability.FULL_TEXT,
                                        "elasticsearch",
                                        "kb-1-fulltext-index",
                                        true
                                )
                        ),
                        true,
                        null,
                        false,
                        false,
                        0.0d,
                        0.7d,
                        0.3d
                )
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1", "doc-2")));

        assertEquals("milvus", plan.primaryRetrievalPlan().recallSpecs().get(0).vectorEngineId());
        assertEquals("elasticsearch", plan.primaryRetrievalPlan().recallSpecs().get(0).fullTextEngineId());
        assertEquals(
                "kb-1-vector-collection",
                plan.primaryRetrievalPlan().recallSpecs().get(0).bindingOf(RetrievalCapability.VECTOR).targetName()
        );
        assertEquals(
                "kb-1-fulltext-index",
                plan.primaryRetrievalPlan().recallSpecs().get(0).bindingOf(RetrievalCapability.FULL_TEXT).targetName()
        );
    }

    @Test
    void plan_failsWhenHybridKnowledgeBaseMissingRequiredBinding() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta(
                        "kb-1",
                        RetrievalMode.HYBRID,
                        "legacy-collection",
                        List.of(new RetrievalBindingMeta(
                                RetrievalCapability.VECTOR,
                                "milvus",
                                "kb-1-vector-collection",
                                true
                        )),
                        true,
                        null,
                        false,
                        false,
                        0.0d,
                        0.7d,
                        0.3d
                )
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        assertThrows(IllegalStateException.class, () ->
                planner.plan(new QueryPlanRequest("hello", List.of("doc-1"))));
    }

    @Test
    void plan_allowsFullTextKnowledgeBaseWithOnlyFullTextBinding() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta(
                        "kb-1",
                        RetrievalMode.FULL_TEXT,
                        "legacy-collection",
                        List.of(new RetrievalBindingMeta(
                                RetrievalCapability.FULL_TEXT,
                                "milvus",
                                "kb-1-fulltext-collection",
                                true
                        )),
                        true,
                        null,
                        true,
                        false,
                        0.0d,
                        0.7d,
                        0.3d
                )
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of()));

        assertEquals(RetrievalMode.FULL_TEXT, plan.primaryRetrievalPlan().recallSpecs().get(0).retrievalMode());
        assertEquals("milvus", plan.primaryRetrievalPlan().recallSpecs().get(0).fullTextEngineId());
        assertEquals(
                "kb-1-fulltext-collection",
                plan.primaryRetrievalPlan().recallSpecs().get(0).bindingOf(RetrievalCapability.FULL_TEXT).targetName()
        );
        assertEquals(true, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
    }

    @Test
    void plan_prefersModelNameMatchingAndKeepsIdCompatibility() {
        QueryPlannerProperties properties = new QueryPlannerProperties();
        properties.getDefaults().setEmbeddingModelId("bge_m3");
        properties.getDefaults().setRerankModelId("jina-reranker");
        properties.getDefaults().setTopK(5);
        properties.getDefaults().setCandidateK(10);
        planner = new StandardRetrievalPlanner(metadataQueryService, properties);

        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, true, false, 0.0d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta(
                        "4d0d3f90-uuid",
                        "bge_m3",
                        ModelType.EMBEDDING,
                        "http://embed",
                        "k",
                        true
                ));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta(
                        "8f1d2e90-uuid",
                        "jina-reranker",
                        ModelType.RERANK,
                        "http://rerank",
                        "k",
                        true
                ));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of()));

        assertEquals("bge_m3", plan.primaryRetrievalPlan().embeddingSpec().modelEndpoint().model());
        assertEquals(RankingSpec.RerankRankingSpec.class, plan.primaryRetrievalPlan().globalRankingSpec().getClass());
    }

    @Test
    void plan_fallsBackToWeightedWhenNoKnowledgeBaseEnablesRerank() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, false, false, 0.0d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of()));

        assertEquals(RankingSpec.WeightedRankingSpec.class, plan.primaryRetrievalPlan().globalRankingSpec().getClass());
        assertEquals(false, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
    }

    @Test
    void plan_buildsBothGlobalAndKnowledgeBaseRerankSpecsWhenEnabled() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, false, false, 0.0d, 0.7d, 0.3d),
                new KnowledgeBaseMeta("kb-2", RetrievalMode.SEMANTIC, "collection-2", true,
                        null, true, false, 0.0d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of()));

        assertEquals(RankingSpec.RerankRankingSpec.class, plan.primaryRetrievalPlan().globalRankingSpec().getClass());
        assertEquals(
                "rerank-model",
                ((RankingSpec.RerankRankingSpec) plan.primaryRetrievalPlan().globalRankingSpec()).modelEndpoint().model()
        );
        assertEquals(false, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
        assertEquals(true, plan.primaryRetrievalPlan().recallSpecs().get(1).hasRerankRankingSpec());
    }

    @Test
    void plan_globalRerankDoesNotPromoteKnowledgeBaseThresholdToFinalThreshold() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, true, true, 0.45d, 0.7d, 0.3d),
                new KnowledgeBaseMeta("kb-2", RetrievalMode.SEMANTIC, "collection-2", true,
                        null, false, true, 0.30d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of()));

        assertEquals(RankingSpec.RerankRankingSpec.class, plan.primaryRetrievalPlan().globalRankingSpec().getClass());
        assertEquals(false, plan.primaryRetrievalPlan().globalScoreThresholdEnabled());
        assertEquals(0.0d, plan.primaryRetrievalPlan().globalScoreThreshold());
        assertEquals(0.45d, plan.primaryRetrievalPlan().recallSpecs().get(0).scoreThreshold());
        assertEquals(0.30d, plan.primaryRetrievalPlan().recallSpecs().get(1).scoreThreshold());
    }

    @Test
    void plan_fallsBackToWeightedWhenRerankModelMissingEvenIfKnowledgeBaseEnablesRerank() {
        QueryPlannerProperties properties = new QueryPlannerProperties();
        properties.getDefaults().setEmbeddingModelId("embed-default");
        properties.getDefaults().setRerankModelId(null);
        properties.getDefaults().setTopK(8);
        properties.getDefaults().setCandidateK(12);
        planner = new StandardRetrievalPlanner(metadataQueryService, properties);

        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, true, false, 0.0d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1")));

        assertEquals(RankingSpec.WeightedRankingSpec.class, plan.primaryRetrievalPlan().globalRankingSpec().getClass());
        assertEquals(false, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
        assertEquals(false, plan.primaryRetrievalPlan().globalScoreThresholdEnabled());
        assertEquals(0.0d, plan.primaryRetrievalPlan().globalScoreThreshold());
    }

    @Test
    void plan_contextUsesRequestedKnowledgeBasesAndFallbackThresholds() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true),
                new KnowledgeBaseMeta("kb-2", RetrievalMode.SEMANTIC, "collection-2", true)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new com.cffex.rag.common.domain.retrieval.RetrievalContext(
                "tenant-1",
                "hello",
                List.of("kb-2"),
                List.of("doc-2"),
                "embed-default",
                null,
                0,
                0,
                Map.of(),
                Map.of()
        ));

        assertEquals(1, plan.retrievalPlans().size());
        assertEquals("kb-2", plan.primaryRetrievalPlan().recallSpecs().get(0).knowledgeBaseId());
        assertEquals(8, plan.primaryRetrievalPlan().topK());
        assertEquals(2, plan.primaryRetrievalPlan().recallSpecs().get(0).candidateK());
    }

    @Test
    void plan_contextDisablesKnowledgeBaseAndGlobalRerankWhenRequested() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        5, true, false, 0.0d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta(
                        "embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta(
                        "rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new com.cffex.rag.common.domain.retrieval.RetrievalContext(
                "tenant-1",
                "hello",
                List.of("kb-1"),
                List.of("doc-1"),
                "embed-default",
                "rerank-default",
                RetrievalMode.FULL_TEXT,
                5,
                20,
                false,
                false,
                0.0d,
                Map.of(),
                Map.of()
        ));

        assertEquals(RankingSpec.WeightedRankingSpec.class,
                plan.primaryRetrievalPlan().globalRankingSpec().getClass());
        assertEquals(RetrievalMode.FULL_TEXT, plan.primaryRetrievalPlan().recallSpecs().get(0).retrievalMode());
        assertEquals(false, plan.primaryRetrievalPlan().recallSpecs().get(0).rerankingEnabled());
        assertEquals(false, plan.primaryRetrievalPlan().recallSpecs().get(0).hasRerankRankingSpec());
    }

    @Test
    void plan_groupsDocumentFiltersByKnowledgeBase() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true),
                new KnowledgeBaseMeta("kb-2", RetrievalMode.HYBRID, "collection-2", true)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });
        doReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "doc-1.txt", null, null),
                "doc-2", new DocumentMeta("doc-2", "kb-2", "doc-2.txt", null, null),
                "doc-3", new DocumentMeta("doc-3", "kb-1", "doc-3.txt", null, null)
        )).when(metadataQueryService).getDocumentMetas(any());

        ExecutionPlan plan = planner.plan(new QueryPlanRequest("hello", List.of("doc-1", "doc-2", "doc-3")));

        assertEquals(List.of("doc-1", "doc-3"), plan.primaryRetrievalPlan().recallSpecs().get(0).docIds());
        assertEquals(List.of("doc-2"), plan.primaryRetrievalPlan().recallSpecs().get(1).docIds());
    }

    @Test
    void plan_contextUsesRequestedTopKOnlyForGlobalOutputAndKeepsKnowledgeBaseTopKForRecall() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        3, true, true, 0.45d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new com.cffex.rag.common.domain.retrieval.RetrievalContext(
                "tenant-1",
                "hello",
                List.of("kb-1"),
                List.of("doc-1", "doc-2"),
                "embed-default",
                "rerank-default",
                8,
                20,
                Map.of(),
                Map.of()
        ));

        assertEquals(8, plan.primaryRetrievalPlan().topK());
        assertEquals(3, plan.primaryRetrievalPlan().recallSpecs().get(0).candidateK());
        assertEquals(3, plan.primaryRetrievalPlan().recallSpecs().get(0).topK());
        assertEquals(0.45d, plan.primaryRetrievalPlan().recallSpecs().get(0).scoreThreshold());
    }

    @Test
    void plan_contextUsesRequestedScoreThresholdAsGlobalThreshold() {
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true,
                        null, true, true, 0.45d, 0.7d, 0.3d),
                new KnowledgeBaseMeta("kb-2", RetrievalMode.SEMANTIC, "collection-2", true,
                        null, true, true, 0.30d, 0.7d, 0.3d)
        ));
        when(metadataQueryService.listModels(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String text = String.valueOf(arg);
            if (text.contains("EMBEDDING")) {
                return List.of(new ModelMeta("embed-default", "embed-model", ModelType.EMBEDDING, "http://embed", "k", true));
            }
            if (text.contains("RERANK")) {
                return List.of(new ModelMeta("rerank-default", "rerank-model", ModelType.RERANK, "http://rerank", "k", true));
            }
            return List.of();
        });

        ExecutionPlan plan = planner.plan(new com.cffex.rag.common.domain.retrieval.RetrievalContext(
                "tenant-1",
                "hello",
                List.of("kb-1", "kb-2"),
                List.of("doc-1", "doc-2"),
                "embed-default",
                "rerank-default",
                8,
                20,
                true,
                0.72d,
                Map.of(),
                Map.of()
        ));

        assertEquals(true, plan.primaryRetrievalPlan().globalScoreThresholdEnabled());
        assertEquals(0.72d, plan.primaryRetrievalPlan().globalScoreThreshold());
        assertEquals(0.45d, plan.primaryRetrievalPlan().recallSpecs().get(0).scoreThreshold());
        assertEquals(0.30d, plan.primaryRetrievalPlan().recallSpecs().get(1).scoreThreshold());
    }


    private static QueryPlannerProperties createProperties() {
        QueryPlannerProperties properties = new QueryPlannerProperties();
        properties.getDefaults().setEmbeddingModelId("embed-default");
        properties.getDefaults().setRerankModelId("rerank-default");
        properties.getDefaults().setTopK(8);
        properties.getDefaults().setCandidateK(12);
        return properties;
    }

    private static String inferKnowledgeBaseId(String docId) {
        if (docId != null && docId.contains("2")) {
            return "kb-2";
        }
        return "kb-1";
    }
}
