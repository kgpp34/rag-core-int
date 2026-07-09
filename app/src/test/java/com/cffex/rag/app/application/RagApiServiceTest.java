package com.cffex.rag.app.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;
import com.cffex.rag.common.domain.retrieval.RetrievalResult;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.common.service.LlmService;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.common.service.QueryPlannerFacade;
import com.cffex.rag.common.service.RetrievalEngine;
import com.cffex.rag.common.service.ConversationMemoryService;

@ExtendWith(MockitoExtension.class)
class RagApiServiceTest {

    @Mock
    private MetadataQueryService metadataQueryService;

    @Mock
    private QueryPlannerFacade queryPlannerFacade;

    @Mock
    private RetrievalEngine retrievalEngine;

    @Mock
    private LlmService llmService;

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private RagApiService ragApiService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultLlmModelId("llm-default");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );
        lenient().when(conversationMemoryService.resolve(any())).thenAnswer(invocation -> {
            ConversationMemoryRequest request = invocation.getArgument(0);
            return new ConversationMemoryContext(request.userId(), null);
        });
        lenient().when(metadataQueryService.getDocumentMetas(any())).thenReturn(Map.of());
    }

    private static ApiModels.RagAnswerRequest simpleAnswerRequest(String query, String systemPrompt) {
        return new ApiModels.RagAnswerRequest(query, "test-user", List.of("doc-1"), null, systemPrompt, null, null, null);
    }

    private static ApiModels.RagAnswerRequest simpleAnswerRequest(String query) {
        return simpleAnswerRequest(query, null);
    }

    @Test
    void answer_prefersRequestSystemPrompt() {
        stubPlanningAndRetrieval("request prompt");
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ragApiService.answer(simpleAnswerRequest("hello", "request prompt"));

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        assertEquals("request prompt", llmCaptor.getValue().messages().get(0).content());
    }

    @Test
    void answer_usesConfiguredDefaultSystemPromptWhenRequestMissing() {
        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ragApiService.answer(simpleAnswerRequest("hello"));

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        assertEquals("configured prompt", llmCaptor.getValue().messages().get(0).content());
        assertEquals("qwen-test", llmCaptor.getValue().modelEndpoint().model());
        assertEquals(0.2d, llmCaptor.getValue().temperature());
        assertEquals(4096, llmCaptor.getValue().maxTokens());
    }

    @Test
    void answer_resolvesDefaultLlmByModelName() {
        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-uuid-1", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ApiModels.RagAnswerResponse response = ragApiService.answer(simpleAnswerRequest("hello"));

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        assertEquals("qwen-test", llmCaptor.getValue().modelEndpoint().model());
        assertEquals("answer", response.answer());
    }

    @Test
    void answer_fallsBackToLegacyModelIdConfiguration() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().getAnswer().setDefaultLlmModelId("llm-default");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ragApiService.answer(simpleAnswerRequest("hello"));

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        assertEquals("qwen-test", llmCaptor.getValue().modelEndpoint().model());
    }

    @Test
    void answer_includesExecutionPlanMetadata() {
        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of("provider", "mock")));

        ApiModels.RagAnswerResponse response = ragApiService.answer(simpleAnswerRequest("hello"));

        assertEquals("answer", response.answer());
        assertEquals(0, response.references().size());
    }

    @Test
    void search_passesPlanTypeAndSystemPromptToPlanner() {
        ExecutionPlan executionPlan = sampleExecutionPlan("planner prompt");
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(retrievalEngine.execute(plan)).thenReturn(sampleRetrieval());

        ragApiService.search(new ApiModels.QueryRequest(
                "hello",
                List.of("doc-1"),
                com.cffex.rag.common.domain.query.PlanType.STANDARD_RETRIEVAL,
                "planner prompt"
        ));

        ArgumentCaptor<QueryPlanRequest> requestCaptor = ArgumentCaptor.forClass(QueryPlanRequest.class);
        verify(queryPlannerFacade).plan(requestCaptor.capture());
        assertEquals(com.cffex.rag.common.domain.query.PlanType.STANDARD_RETRIEVAL, requestCaptor.getValue().planType());
        assertEquals("planner prompt", requestCaptor.getValue().systemPrompt());
    }

    @Test
    void search_usesRetrievalContextWhenTuningParamsProvided() {
        ExecutionPlan executionPlan = sampleExecutionPlan("planner prompt");
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(RetrievalContext.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(sampleRetrieval());
        when(metadataQueryService.listKnowledgeBases(any(com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition.class))).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta("kb-1", com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID, "collection-1", true)
        ));

        ragApiService.search(new ApiModels.QueryRequest(
                "hello",
                List.of("doc-1"),
                com.cffex.rag.common.domain.query.PlanType.STANDARD_RETRIEVAL,
                "planner prompt",
                new ApiModels.RetrievalTuning(8, 20, 0.72d),
                null
        ));

        ArgumentCaptor<RetrievalContext> contextCaptor = ArgumentCaptor.forClass(RetrievalContext.class);
        verify(queryPlannerFacade).plan(contextCaptor.capture());
        assertEquals(List.of("kb-1"), contextCaptor.getValue().targetKnowledgeBaseIds());
        assertEquals(8, contextCaptor.getValue().topK());
        assertEquals(20, contextCaptor.getValue().candidateK());
        assertEquals(true, contextCaptor.getValue().scoreThresholdEnabled());
        assertEquals(0.72d, contextCaptor.getValue().scoreThreshold());
    }

    @Test
    void answer_usesRetrievalContextAndPreservesRequestSystemPromptWhenTuningParamsProvided() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(RetrievalContext.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(sampleRetrieval());
        when(metadataQueryService.listKnowledgeBases(any(com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition.class))).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta("kb-1", com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID, "collection-1", true)
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ragApiService.answer(new ApiModels.RagAnswerRequest(
                "hello",
                "test-user",
                List.of("doc-1"),
                null,
                "request prompt",
                new ApiModels.RetrievalTuning(6, 18, 0.66d),
                null,
                null
        ));

        ArgumentCaptor<RetrievalContext> contextCaptor = ArgumentCaptor.forClass(RetrievalContext.class);
        verify(queryPlannerFacade).plan(contextCaptor.capture());
        assertEquals(6, contextCaptor.getValue().topK());
        assertEquals(18, contextCaptor.getValue().candidateK());
        assertEquals(0.66d, contextCaptor.getValue().scoreThreshold());

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        assertEquals("request prompt", llmCaptor.getValue().messages().get(0).content());
    }

    @Test
    void answer_wrapsRetrievalFailureWithSpecificErrorCode() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenThrow(new RuntimeException("milvus timeout"));

        RagServiceException ex = assertThrows(RagServiceException.class, () -> ragApiService.answer(
                simpleAnswerRequest("hello")
        ));

        assertEquals(RagErrorCode.RETRIEVAL_FAILED, ex.errorCode());
        assertEquals("知识检索失败: milvus timeout", ex.getMessage());
    }

    @Test
    void answer_propagatesStructuredLlmFailure() {
        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenThrow(new RagServiceException(
                RagErrorCode.LLM_UNAVAILABLE,
                "LLM 服务不可达"
        ));

        RagServiceException ex = assertThrows(RagServiceException.class, () -> ragApiService.answer(
                simpleAnswerRequest("hello")
        ));

        assertEquals(RagErrorCode.LLM_UNAVAILABLE, ex.errorCode());
        assertEquals("LLM 服务不可达", ex.getMessage());
    }

    @Test
    void search_wrapsPlanningFailureWithSpecificErrorCode() {
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenThrow(new RuntimeException("planner unavailable"));

        RagServiceException ex = assertThrows(RagServiceException.class, () -> ragApiService.search(
                new ApiModels.QueryRequest("hello", List.of("doc-1"), null, null)
        ));

        assertEquals(RagErrorCode.PLANNING_FAILED, ex.errorCode());
        assertEquals("检索规划失败: planner unavailable", ex.getMessage());
    }

    @Test
    void answer_buildsReferencesFromChunkMetadata() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setDifyFilesUrl("http://dify.example.com/v1/files");
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(new RetrievalResult(
                "req-1",
                List.of(
                        new RetrievedChunk("chunk-1", "doc-1", "kb-1", 0.9, 0.2, 0.8, "content 1",
                                Map.of("document_name", "政策文件.pdf", "upload_file_id", "file-abc")),
                        new RetrievedChunk("chunk-2", "doc-1", "kb-1", 0.8, 0.1, 0.7, "content 2",
                                Map.of("document_name", "政策文件.pdf", "upload_file_id", "file-abc")),
                        new RetrievedChunk("chunk-3", "doc-2", "kb-1", 0.7, null, 0.6, "content 3",
                                Map.of("document_name", "技术标准.docx", "upload_file_id", "file-xyz"))
                ),
                Map.of()
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1", "doc-2"))).thenReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "政策文件.pdf", "file-abc", "upload_files/tenant-a/storage-policy.pdf"),
                "doc-2", new DocumentMeta("doc-2", "kb-1", "技术标准.docx", "file-xyz", "upload_files/tenant-a/storage-standard.docx")
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("answer", "stop", Map.of()));

        ApiModels.RagAnswerResponse response = ragApiService.answer(simpleAnswerRequest("hello"));

        assertEquals("answer", response.answer());
        assertEquals(2, response.references().size());
        assertEquals("政策文件.pdf", response.references().get(0).fileName());
        assertEquals("http://dify.example.com/v1/files/storage-policy.pdf", response.references().get(0).path());
        assertEquals("技术标准.docx", response.references().get(1).fileName());
        assertEquals("http://dify.example.com/v1/files/storage-standard.docx", response.references().get(1).path());

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService).generate(llmCaptor.capture());
        String userPrompt = llmCaptor.getValue().messages().get(1).content();
        assertFalse(userPrompt.contains("http://dify.example.com/v1/files/storage-policy.pdf"));
        assertFalse(userPrompt.contains("来源编号："));
        assertTrue(userPrompt.contains("[片段 1] 来源文件：政策文件.pdf"));
        assertTrue(userPrompt.contains("[片段 2] 来源文件：政策文件.pdf"));
        assertTrue(userPrompt.contains("[片段 3] 来源文件：技术标准.docx"));
    }

    @Test
    void answer_deduplicatesRepeatedReferenceLinesInGeneratedAnswer() {
        stubPlanningAndRetrieval(null);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("""
                答案正文。

                ## 参考资料
                [1] 《政策文件.pdf》
                [1] 《政策文件.pdf》
                [2] 《技术标准.docx》
                """, "stop", Map.of()));

        ApiModels.RagAnswerResponse response = ragApiService.answer(simpleAnswerRequest("hello"));

        assertEquals("答案正文。", response.answer());
    }

    @Test
    void answer_omitsReferencesWhenGeneratedAnswerDoesNotCiteSources() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setDifyFilesUrl("http://dify.example.com/v1/files");
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(new RetrievalResult(
                "req-1",
                List.of(new RetrievedChunk("chunk-1", "doc-1", "kb-1", 0.9, 0.2, 0.8, "unrelated content",
                        Map.of("document_name", "无关文件.pdf", "upload_file_id", "file-unrelated"))),
                Map.of()
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse(
                "当前检索结果与问题明显不相关，无法基于参考资料回答。",
                "stop",
                Map.of()
        ));

        ApiModels.RagAnswerResponse response = ragApiService.answer(simpleAnswerRequest("hello"));

        assertEquals("当前检索结果与问题明显不相关，无法基于参考资料回答。", response.answer());
        assertTrue(response.references().isEmpty());
    }

    @Test
    void search_executesRewrittenQueriesInParallel() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta(
                        "kb-1",
                        com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID,
                        "collection-1",
                        true
                )
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("rewrite hello", "stop", Map.of()));

        CountDownLatch retrievalsEntered = new CountDownLatch(2);
        doAnswer(invocation -> {
            RetrievalPlan plan = invocation.getArgument(0, RetrievalPlan.class);
            retrievalsEntered.countDown();
            assertTrue(
                    retrievalsEntered.await(2, TimeUnit.SECONDS),
                    "rewritten queries should be retrieved in parallel"
            );
            return new RetrievalResult(
                    "req-" + plan.query(),
                    List.of(new RetrievedChunk(
                            "chunk-" + plan.query(),
                            "doc-1",
                            "kb-1",
                            0.9,
                            0.2,
                            0.8,
                            "content " + plan.query(),
                            Map.of()
                    )),
                    Map.of()
            );
        }).when(retrievalEngine).execute(any(RetrievalPlan.class));

        ApiModels.RetrievalResponse response = ragApiService.search(new ApiModels.QueryRequest(
                "hello",
                List.of("doc-1"),
                null,
                null,
                null,
                new ApiModels.QueryRewriteConfig(true, "rewrite prompt")
        ));

        assertEquals(2, response.chunks().size());
        assertEquals("chunk-hello", response.chunks().get(0).chunkId());
        assertEquals("chunk-rewrite hello", response.chunks().get(1).chunkId());
    }

    @Test
    void search_queryRewriteOmitsOriginalQueryWhenDependsOnHistory() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta(
                        "kb-1",
                        com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID,
                        "collection-1",
                        true
                )
        ));
        when(llmService.generate(any())).thenReturn(new LlmResponse("""
                {
                  "dependsOnHistory": true,
                  "resolvedQuestion": "项目上党办会额度要求是什么？",
                  "retrievalQueries": [
                    "项目上党办会额度要求",
                    "党办会 项目 额度 要求"
                  ],
                  "reason": "当前问题是追问"
                }
                """, "stop", Map.of()));
        when(retrievalEngine.execute(any(RetrievalPlan.class))).thenAnswer(invocation -> {
            RetrievalPlan plan = invocation.getArgument(0, RetrievalPlan.class);
            return new RetrievalResult(
                    "req-" + plan.query(),
                    List.of(new RetrievedChunk(
                            "chunk-" + plan.query(),
                            "doc-1",
                            "kb-1",
                            0.9,
                            0.2,
                            0.8,
                            "content " + plan.query(),
                            Map.of()
                    )),
                    Map.of()
            );
        });

        ragApiService.search(new ApiModels.QueryRequest(
                "额度要求呢？",
                List.of("doc-1"),
                null,
                null,
                null,
                new ApiModels.QueryRewriteConfig(true, "rewrite prompt")
        ));

        ArgumentCaptor<RetrievalPlan> planCaptor = ArgumentCaptor.forClass(RetrievalPlan.class);
        verify(retrievalEngine, times(2)).execute(planCaptor.capture());
        List<String> executedQueries = planCaptor.getAllValues().stream().map(RetrievalPlan::query).toList();
        assertFalse(executedQueries.contains("额度要求呢？"));
        assertTrue(executedQueries.contains("项目上党办会额度要求"));
        assertTrue(executedQueries.contains("党办会 项目 额度 要求"));
    }

    @Test
    void answer_queryRewriteIncludesRecentUserHistoryWhenMemoryEnabled() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        doAnswer(invocation -> new ConversationMemoryContext("test-user", "conv-1"))
                .when(conversationMemoryService)
                .resolve(any());
        when(conversationMemoryService.recentUserMessages("conv-1", 3)).thenReturn(List.of(
                "程序化交易异常报送流程是什么？",
                "需要准备哪些材料？"
        ));
        when(llmService.generate(any()))
                .thenReturn(new LlmResponse("程序化交易异常报送处罚标准", "stop", Map.of()))
                .thenReturn(new LlmResponse("answer", "stop", Map.of()));
        when(retrievalEngine.execute(any(RetrievalPlan.class))).thenAnswer(invocation -> {
            RetrievalPlan plan = invocation.getArgument(0, RetrievalPlan.class);
            return new RetrievalResult(
                    "req-" + plan.query(),
                    List.of(new RetrievedChunk(
                            "chunk-" + plan.query(),
                            "doc-1",
                            "kb-1",
                            0.9,
                            0.2,
                            0.8,
                            "content " + plan.query(),
                            Map.of()
                    )),
                    Map.of()
            );
        });

        ragApiService.answer(new ApiModels.RagAnswerRequest(
                "那处罚标准呢？",
                "test-user",
                List.of("doc-1"),
                null,
                null,
                null,
                new ApiModels.MemoryConfig("conv-1"),
                new ApiModels.QueryRewriteConfig(true, "rewrite prompt")
        ));

        ArgumentCaptor<LlmRequest> llmCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService, times(2)).generate(llmCaptor.capture());
        String rewriteUserPrompt = llmCaptor.getAllValues().get(0).messages().get(1).content();
        assertTrue(rewriteUserPrompt.contains("当前问题：那处罚标准呢？"));
        assertTrue(rewriteUserPrompt.contains("最近历史问题："));
        assertTrue(rewriteUserPrompt.contains("程序化交易异常报送流程是什么？"));
        assertTrue(rewriteUserPrompt.contains("如果当前问题与历史问题毫无关系，请完全忽略历史问题"));
    }

    @Test
    void streamAnswer_emitsProgressBeforeAnswerEvents() {
        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(any(RetrievalPlan.class), any())).thenReturn(sampleRetrieval());
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta(
                        "kb-1",
                        com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID,
                        "collection-1",
                        true,
                        null,
                        false,
                        false,
                        0.0d,
                        0.7d,
                        0.3d,
                        "测试知识库"
                )
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<LlmStreamChunk> consumer = invocation.getArgument(1, java.util.function.Consumer.class);
            consumer.accept(new LlmStreamChunk("hello", null, false, Map.of()));
            consumer.accept(new LlmStreamChunk("", "stop", true, Map.of(
                    "prompt_tokens", 10,
                    "completion_tokens", 2,
                    "total_tokens", 12
            )));
            return null;
        }).when(llmService).streamGenerate(any(), any());

        List<RagApiService.StreamEvent> events = new ArrayList<>();
        ragApiService.streamAnswer(simpleAnswerRequest("hello"), events::add);

        assertEquals(
                List.of("rag_progress", "rag_progress", "rag_progress", "rag_progress", "delta", "rag_progress", "done"),
                events.stream().map(RagApiService.StreamEvent::name).toList()
        );
        List<ApiModels.RagProgressEvent> progressEvents = events.stream()
                .filter(event -> "rag_progress".equals(event.name()))
                .map(event -> (ApiModels.RagProgressEvent) event.payload())
                .toList();
        assertEquals(
                List.of("retrieval", "retrieval", "answer_generation", "answer_generation", "answer_generation"),
                progressEvents.stream().map(ApiModels.RagProgressEvent::stage).toList()
        );
        assertEquals(
                List.of("started", "completed", "started", "milestone", "completed"),
                progressEvents.stream().map(ApiModels.RagProgressEvent::status).toList()
        );
        assertEquals(12, progressEvents.get(4).details().get("totalTokens"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> knowledgeBases = (List<Map<String, Object>>) progressEvents.get(0)
                .details()
                .get("knowledgeBases");
        assertEquals(List.of(Map.of("knowledgeBaseId", "kb-1", "name", "测试知识库")), knowledgeBases);
    }

    @Test
    void streamAnswer_replacesModelReferenceSectionWithBackendReferences() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setDifyFilesUrl("http://dify.example.com/v1/files");
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultLlmModelId("llm-default");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(any(RetrievalPlan.class), any())).thenReturn(new RetrievalResult(
                "req-1",
                List.of(new RetrievedChunk("chunk-1", "doc-1", "kb-1", 0.9, 0.2, 0.8, "content 1",
                        Map.of("document_name", "政策文件.pdf", "upload_file_id", "file-abc"))),
                Map.of()
        ));
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta(
                        "kb-1",
                        com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID,
                        "collection-1",
                        true,
                        null,
                        false,
                        false,
                        0.0d,
                        0.7d,
                        0.3d,
                        "测试知识库"
                )
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        when(metadataQueryService.getDocumentMetas(List.of("doc-1"))).thenReturn(Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "政策文件.pdf", "file-abc", "upload_files/tenant-a/storage-policy.pdf")
        ));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<LlmStreamChunk> consumer = invocation.getArgument(1, java.util.function.Consumer.class);
            consumer.accept(new LlmStreamChunk("正文引用 [1]。\n\n## 参", null, false, Map.of()));
            consumer.accept(new LlmStreamChunk("考资料\n[1] [政策文件.pdf](http://http://bad.example.com/file.pdf)", null, false, Map.of()));
            consumer.accept(new LlmStreamChunk("", "stop", true, Map.of()));
            return null;
        }).when(llmService).streamGenerate(any(), any());

        List<RagApiService.StreamEvent> events = new ArrayList<>();
        ragApiService.streamAnswer(simpleAnswerRequest("hello"), events::add);

        String streamedAnswer = events.stream()
                .filter(event -> "delta".equals(event.name()))
                .map(event -> ((ApiModels.RagAnswerStreamDelta) event.payload()).content())
                .reduce("", String::concat);
        assertEquals("""
                正文引用 [1]。

                ---

                ###### 参考资料
                [1] [政策文件.pdf](http://dify.example.com/v1/files/storage-policy.pdf)""", streamedAnswer);
    }

    @Test
    void answer_failsWhenDifyFilesUrlHasRepeatedScheme() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setDifyFilesUrl("http://http://dify.example.com/v1/files");
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultLlmModelId("llm-default");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(new RetrievalResult(
                "req-1",
                List.of(new RetrievedChunk("chunk-1", "doc-1", "kb-1", 0.9, 0.2, 0.8, "content 1",
                        Map.of("document_name", "政策文件.pdf", "upload_file_id", "file-abc"))),
                Map.of()
        ));
        RagServiceException ex = assertThrows(
                RagServiceException.class,
                () -> ragApiService.answer(simpleAnswerRequest("hello"))
        );

        assertEquals(RagErrorCode.INVALID_CONFIGURATION, ex.errorCode());
        assertTrue(ex.getMessage().contains("app.rag.dify-files-url 配置非法，存在重复协议"));
    }

    @Test
    void streamAnswer_appendsAllBackendReferencesWithoutBodyCitations() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setDifyFilesUrl("http://dify.example.com/v1/files");
        appProperties.getRag().getAnswer().setDefaultLlmModel("qwen-test");
        appProperties.getRag().getAnswer().setDefaultLlmModelId("llm-default");
        appProperties.getRag().getAnswer().setDefaultTemperature(0.2d);
        appProperties.getRag().getAnswer().setDefaultMaxTokens(4096);
        appProperties.getRag().getAnswer().setDefaultSystemPrompt("configured prompt");
        ragApiService = new RagApiService(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService
        );

        ExecutionPlan executionPlan = sampleExecutionPlan(null);
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        List<RetrievedChunk> chunks = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> new RetrievedChunk(
                        "chunk-" + index,
                        "doc-" + index,
                        "kb-1",
                        1.0d - index * 0.01d,
                        null,
                        1.0d - index * 0.01d,
                        "content " + index,
                        Map.of("document_name", "文件" + index + ".pdf", "upload_file_id", "file-" + index)
                ))
                .toList();
        when(retrievalEngine.execute(any(RetrievalPlan.class), any())).thenReturn(new RetrievalResult(
                "req-1",
                chunks,
                Map.of()
        ));
        when(metadataQueryService.listKnowledgeBases(any())).thenReturn(List.of(
                new com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta(
                        "kb-1",
                        com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID,
                        "collection-1",
                        true
                )
        ));
        when(metadataQueryService.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm-default", "qwen-test", ModelType.LLM, "http://llm", "secret", true)
        ));
        java.util.LinkedHashMap<String, DocumentMeta> documentMetas = new java.util.LinkedHashMap<>();
        IntStream.rangeClosed(1, 10).forEach(index -> documentMetas.put(
                "doc-" + index,
                new DocumentMeta(
                        "doc-" + index,
                        "kb-1",
                        "文件" + index + ".pdf",
                        "file-" + index,
                        "upload_files/tenant-a/storage-" + index + ".pdf"
                )
        ));
        when(metadataQueryService.getDocumentMetas(IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "doc-" + index)
                .toList())).thenReturn(documentMetas);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<LlmStreamChunk> consumer = invocation.getArgument(1, java.util.function.Consumer.class);
            consumer.accept(new LlmStreamChunk("正文不输出来源编号。", null, false, Map.of()));
            consumer.accept(new LlmStreamChunk("", "stop", true, Map.of()));
            return null;
        }).when(llmService).streamGenerate(any(), any());

        List<RagApiService.StreamEvent> events = new ArrayList<>();
        ragApiService.streamAnswer(simpleAnswerRequest("hello"), events::add);

        String streamedAnswer = events.stream()
                .filter(event -> "delta".equals(event.name()))
                .map(event -> ((ApiModels.RagAnswerStreamDelta) event.payload()).content())
                .reduce("", String::concat);
        assertTrue(streamedAnswer.contains("---\n\n###### 参考资料"));
        assertTrue(streamedAnswer.contains("[1] [文件1.pdf](http://dify.example.com/v1/files/storage-1.pdf)"));
        assertTrue(streamedAnswer.contains("[10] [文件10.pdf](http://dify.example.com/v1/files/storage-10.pdf)"));
    }

    private void stubPlanningAndRetrieval(String systemPrompt) {
        ExecutionPlan executionPlan = sampleExecutionPlan(systemPrompt);
        RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
        when(queryPlannerFacade.plan(any(QueryPlanRequest.class))).thenReturn(executionPlan);
        when(retrievalEngine.execute(plan)).thenReturn(sampleRetrieval());
    }

    private ExecutionPlan sampleExecutionPlan(String systemPrompt) {
        return new ExecutionPlan(
                com.cffex.rag.common.domain.query.PlanType.STANDARD_RETRIEVAL,
                "hello",
                systemPrompt,
                List.of(new RetrievalPlan(
                        "hello",
                        new EmbeddingSpec(new ModelEndpointSpec("http://embed", "token", "embed-model")),
                        List.of(new KnowledgeBaseRecallSpec("kb-1", "collection-1", com.cffex.rag.common.domain.metadata.RetrievalMode.HYBRID, List.of("doc-1"), 20)),
                        RankingSpec.WeightedRankingSpec.equalWeight(),
                        5
                )),
                Map.of()
        );
    }

    private RetrievalResult sampleRetrieval() {
        return new RetrievalResult(
                "req-1",
                List.of(new RetrievedChunk("chunk-1", "doc-1", "kb-1", 0.9, 0.2, 0.8, "chunk content", Map.of())),
                Map.of()
        );
    }
}
