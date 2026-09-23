package com.cffex.rag.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.service.*;
import com.cffex.rag.trace.application.NoOpTraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;

class RagCapabilityRoutingTest {

    private final MetadataQueryService metadata = mock(MetadataQueryService.class);
    private final QueryPlannerFacade planner = mock(QueryPlannerFacade.class);
    private final RetrievalEngine retrieval = mock(RetrievalEngine.class);
    private final LlmService llm = mock(LlmService.class);
    private final ConversationMemoryService memory = mock(ConversationMemoryService.class);
    private final AgenticRagClient agentic = mock(AgenticRagClient.class);
    private final AgenticRunStore runStore = mock(AgenticRunStore.class);
    private final AppProperties properties = new AppProperties();

    private RagApiService service(String classification) {
        properties.getRag().getAnswer().setDefaultLlmModel("test-model");
        when(metadata.listModels(any())).thenReturn(List.of(
                new ModelMeta("llm", "test-model", ModelType.LLM, "http://llm", "key", true)));
        when(llm.generate(any())).thenReturn(new LlmResponse(classification, "stop", Map.of()));
        return new RagApiService(metadata, planner, retrieval, llm, properties, memory,
                new NoOpTraceRecorder(), new TraceProperties(), agentic, runStore);
    }

    private ApiModels.RagAnswerRequest request(PlanType type, String query, ApiModels.MemoryConfig memoryConfig) {
        return new ApiModels.RagAnswerRequest(query, "user", List.of(), type, null, null, memoryConfig,
                new ApiModels.QueryRewriteConfig(true, null));
    }

    private void stubKnowledgeBases() {
        when(metadata.listKnowledgeBases(any())).thenReturn(List.of(new KnowledgeBaseMeta(
                "kb1", RetrievalMode.HYBRID, "collection", true, null, false, false, 0, 0.7, 0.3, "交易规则")));
    }

    @ParameterizedTest
    @EnumSource(PlanType.class)
    void jsonIntroductionSkipsBothPipelinesAndHasEmptyReferences(PlanType type) {
        RagApiService service = service("{\"intent\":\"CAPABILITY_INTRO\"}");
        stubKnowledgeBases();
        ApiModels.RagAnswerResponse response = service.answer(request(type, "你能解答什么问题？", null));
        assertTrue(response.answer().contains("交易规则"));
        assertTrue(response.answer().contains("深度思考模式"));
        assertTrue(response.references().isEmpty());
        verify(llm).generate(any());
        verifyNoMoreInteractions(llm);
        verifyNoInteractions(planner, retrieval, agentic, runStore, memory);
    }

    @ParameterizedTest
    @EnumSource(PlanType.class)
    void sseIntroductionEndsWithDeltaAndDone(PlanType type) {
        RagApiService service = service("{\"intent\":\"CAPABILITY_INTRO\"}");
        stubKnowledgeBases();
        List<RagApiService.StreamEvent> events = new ArrayList<>();
        service.streamAnswer(request(type, "你有哪些模式？", null), events::add);
        assertEquals(List.of("delta", "done"), events.stream().map(RagApiService.StreamEvent::name).toList());
        ApiModels.RagAnswerStreamDelta delta = (ApiModels.RagAnswerStreamDelta) events.getFirst().payload();
        assertTrue(delta.content().contains("交易规则"));
        ApiModels.RagAnswerStreamDone done = (ApiModels.RagAnswerStreamDone) events.getLast().payload();
        assertEquals("stop", done.finishReason());
        verify(llm).generate(any());
        verifyNoMoreInteractions(llm);
        verifyNoInteractions(planner, retrieval, agentic, runStore, memory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"你能解释保证金制度吗？", "你能干什么？顺便解释一下保证金。", "系统X有哪些功能？"})
    void knowledgeAndMixedQuestionsContinuePlanning(String query) {
        RagApiService service = service("{\"intent\":\"KNOWLEDGE_QUERY\"}");
        RuntimeException marker = new IllegalStateException("planning reached");
        when(planner.plan(any(QueryPlanRequest.class))).thenThrow(marker);
        assertSame(marker, assertThrows(RuntimeException.class,
                () -> service.answer(request(PlanType.STANDARD_RETRIEVAL, query, null))).getCause());
        verify(planner).plan(any(QueryPlanRequest.class));
        verifyNoInteractions(agentic, runStore);
    }

    @Test
    void failedClassificationContinuesPlanningForBothTransports() {
        RagApiService service = service("bad json");
        RuntimeException marker = new IllegalStateException("planning reached");
        when(planner.plan(any(QueryPlanRequest.class))).thenThrow(marker);
        ApiModels.RagAnswerRequest request = request(PlanType.STANDARD_RETRIEVAL, "问题", null);
        assertSame(marker, assertThrows(RuntimeException.class, () -> service.answer(request)).getCause());
        assertSame(marker, assertThrows(RuntimeException.class,
                () -> service.streamAnswer(request, event -> {})).getCause());
        verify(planner, times(2)).plan(any(QueryPlanRequest.class));
    }

    @Test
    void introductionIsSavedAsExchangeWhenMemoryEnabled() {
        RagApiService service = service("{\"intent\":\"CAPABILITY_INTRO\"}");
        stubKnowledgeBases();
        when(memory.resolve(any())).thenReturn(new ConversationMemoryContext("user", "conversation"));
        ApiModels.RagAnswerRequest request = request(PlanType.AGENTIC_RAG, "你是谁？", new ApiModels.MemoryConfig("conversation"));
        ApiModels.RagAnswerResponse response = service.answer(request);
        verify(memory).appendExchange("conversation", "你是谁？", response.answer());
        verifyNoInteractions(agentic, planner, retrieval, runStore);
    }
}
