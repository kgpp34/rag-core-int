package com.cffex.rag.app.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMessage;
import com.cffex.rag.common.service.ConversationMemoryService;
import com.cffex.rag.common.service.LlmService;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.common.service.QueryPlannerFacade;
import com.cffex.rag.common.service.RetrievalEngine;
import com.cffex.rag.trace.application.NoOpTraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgenticRagRoutingTest {

    private static AppProperties propertiesWithoutIntent() {
        AppProperties properties = new AppProperties();
        properties.getRag().getIntent().setEnabled(false);
        return properties;
    }

    @Test
    void streamAnswerRoutesAgenticRequestAndMapsEvents() throws Exception {
        AgenticRagClient client = mock(AgenticRagClient.class);
        AgenticRunStore runStore = mock(AgenticRunStore.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        when(memoryService.resolve(any())).thenReturn(new ConversationMemoryContext("user-1", "conv-1"));
        when(memoryService.recentMessages("conv-1", 20)).thenReturn(List.of(
                new ConversationMessage("user", "previous question"),
                new ConversationMessage("assistant", "previous answer")
        ));
        when(runStore.claimMemoryWrite(eq(runId), any())).thenReturn(true);
        when(client.createRun(List.of(
                new AgenticRagClient.AgenticMessage("user", "previous question"),
                new AgenticRagClient.AgenticMessage("assistant", "previous answer"),
                new AgenticRagClient.AgenticMessage("user", "hello")
        ), List.of("doc-1"), null))
                .thenReturn(runId);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<AgenticRagClient.AgenticEvent> consumer = invocation.getArgument(1);
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "1", "run.started", mapper.readTree("{\"stage\":\"run\",\"status\":\"started\"}")));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "2", "answer.delta", mapper.readTree("{\"delta\":\"answer\"}")));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "3", "answer.references", mapper.readTree(
                            "{\"reference_count\":0,\"references\":[],\"references_truncated\":false}")));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "4", "run.completed", mapper.readTree("{\"stage\":\"run\"}")));
            return null;
        }).when(client).streamEvents(any(), any(), any());
        when(client.getRun(runId, null)).thenReturn(new AgenticRagClient.AgenticRunResult(
                "completed", mapper.readTree(
                        "{\"answer\":\"answer\",\"citations\":[],\"partial\":false,\"abstain\":false}"), null));

        RagApiService service = new RagApiService(
                mock(MetadataQueryService.class),
                mock(QueryPlannerFacade.class),
                mock(RetrievalEngine.class),
                mock(LlmService.class),
                propertiesWithoutIntent(),
                memoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                client,
                runStore
        );
        ApiModels.RagAnswerRequest request = new ApiModels.RagAnswerRequest(
                "hello", "user-1", List.of("doc-1"), PlanType.AGENTIC_RAG, null, null,
                new ApiModels.MemoryConfig("conv-1"), null);
        List<RagApiService.StreamEvent> events = new ArrayList<>();

        service.streamAnswer(request, events::add);

        assertEquals(List.of("rag_progress", "delta", "reference", "done"),
                events.stream().map(RagApiService.StreamEvent::name).toList());
        assertEquals("answer", ((ApiModels.RagAnswerStreamDelta) events.get(1).payload()).content());
        assertEquals(List.of(), ((ApiModels.RagReferenceEvent) events.get(2).payload()).references());
        verify(runStore).create(runId, null, "conv-1", "user-1", "hello");
        verify(runStore).markCompleted(any(), any());
        verify(memoryService).appendExchangeOnce(runId.toString(), "conv-1", "hello", "answer");
        verify(runStore).claimMemoryWrite(eq(runId), any());
        verify(runStore).markMemoryWritten(runId);
    }

    @Test
    void streamAnswerReconcilesCompletedRunWhenSseEndsBeforeTerminalEvent() throws Exception {
        AgenticRagClient client = mock(AgenticRagClient.class);
        AgenticRunStore runStore = mock(AgenticRunStore.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        when(memoryService.resolve(any())).thenReturn(new ConversationMemoryContext("user-1", null));
        when(client.createRun(any(), any(), any())).thenReturn(runId);
        when(client.getRun(runId, null)).thenReturn(new AgenticRagClient.AgenticRunResult(
                "completed",
                mapper.readTree("""
                        {
                          "answer": "reconciled answer",
                          "citations": [],
                          "partial": false,
                          "abstain": false
                        }
                        """),
                null
        ));

        RagApiService service = new RagApiService(
                mock(MetadataQueryService.class),
                mock(QueryPlannerFacade.class),
                mock(RetrievalEngine.class),
                mock(LlmService.class),
                propertiesWithoutIntent(),
                memoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                client,
                runStore
        );
        List<RagApiService.StreamEvent> events = new ArrayList<>();

        service.streamAnswer(new ApiModels.RagAnswerRequest(
                "hello", "user-1", List.of(), PlanType.AGENTIC_RAG, null, null, null, null), events::add);

        assertEquals(List.of("reference", "done"), events.stream().map(RagApiService.StreamEvent::name).toList());
        assertEquals("completed",
                ((ApiModels.RagAnswerStreamDone) events.get(1).payload()).metadata().get("status"));
        verify(runStore).markCompleted(eq(runId), any());
    }

    @Test
    void answerSupportsAgenticAndNormalizesCitations() throws Exception {
        AgenticRagClient client = mock(AgenticRagClient.class);
        AgenticRunStore runStore = mock(AgenticRunStore.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        MetadataQueryService metadataService = mock(MetadataQueryService.class);
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        when(memoryService.resolve(any())).thenReturn(new ConversationMemoryContext("user-1", null));
        when(client.createRun(any(), any(), any())).thenReturn(runId);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<AgenticRagClient.AgenticEvent> consumer = invocation.getArgument(1);
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "1", "run.completed", mapper.createObjectNode()));
            return null;
        }).when(client).streamEvents(any(), any(), any());
        when(client.getRun(runId, null)).thenReturn(new AgenticRagClient.AgenticRunResult(
                "completed",
                mapper.readTree("""
                        {
                          "answer": "final answer",
                          "citations": [{"document_id": "doc-1", "knowledge_base_id": "kb-1", "metadata": {}}],
                          "partial": false,
                          "abstain": false
                        }
                        """),
                null
        ));
        when(metadataService.getDocumentMetas(List.of("doc-1"))).thenReturn(java.util.Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "policy.pdf", "upload-1", null)
        ));
        when(metadataService.listKnowledgeBases(any(
                com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition.class))).thenReturn(List.of(
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
                        "业务规则库"
                )
        ));
        AppProperties properties = new AppProperties();
        properties.getRag().getIntent().setEnabled(false);
        properties.getRag().setDifyFilesUrl("http://files");
        RagApiService service = new RagApiService(
                metadataService,
                mock(QueryPlannerFacade.class),
                mock(RetrievalEngine.class),
                mock(LlmService.class),
                properties,
                memoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                client,
                runStore
        );

        ApiModels.RagAnswerResponse response = service.answer(new ApiModels.RagAnswerRequest(
                "hello", "user-1", List.of("doc-1"), PlanType.AGENTIC_RAG, null, null, null, null));

        assertEquals("final answer", response.answer());
        assertEquals(List.of(new ApiModels.Reference(
                        "policy.pdf",
                        "http://files/upload-1.pdf",
                        "doc-1",
                        "业务规则库"
                )),
                response.references());
        verify(runStore).markCompleted(any(), any());
    }

    @Test
    void streamAnswerKeepsOnlyDocumentsCitedByAgentic() throws Exception {
        AgenticRagClient client = mock(AgenticRagClient.class);
        AgenticRunStore runStore = mock(AgenticRunStore.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        MetadataQueryService metadataService = mock(MetadataQueryService.class);
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        when(memoryService.resolve(any())).thenReturn(new ConversationMemoryContext("user-1", null));
        when(client.createRun(any(), any(), any())).thenReturn(runId);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<AgenticRagClient.AgenticEvent> consumer = invocation.getArgument(1);
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "1", "answer.delta", mapper.readTree("{\"delta\":\"answer[2]\"}")));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "2", "answer.references", mapper.readTree("""
                            {
                              "reference_count": 1,
                              "references_truncated": false,
                              "references": [
                                {"citation_no": 2, "document_id": "doc-2", "document_name": "cited.pdf"}
                              ]
                            }
                            """)));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "3", "run.completed", mapper.readTree("{\"stage\":\"run\"}")));
            return null;
        }).when(client).streamEvents(any(), any(), any());
        when(client.getRun(runId, null)).thenReturn(new AgenticRagClient.AgenticRunResult(
                "completed",
                mapper.readTree("""
                        {
                          "answer": "answer[2]",
                          "citations": [
                            {"document_id": "doc-1", "knowledge_base_id": "kb-1",
                             "metadata": {"document_name": "unused.pdf"}},
                            {"document_id": "doc-2", "knowledge_base_id": "kb-1",
                             "metadata": {"document_name": "cited.pdf"}}
                          ],
                          "partial": false,
                          "abstain": false
                        }
                        """),
                null
        ));
        when(metadataService.getDocumentMetas(List.of("doc-2"))).thenReturn(java.util.Map.of(
                "doc-2", new DocumentMeta("doc-2", "kb-1", "cited.pdf", "upload-2", null)
        ));
        when(metadataService.listKnowledgeBases(any(
                com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition.class))).thenReturn(List.of(
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
                        "业务规则库"
                )
        ));
        AppProperties properties = new AppProperties();
        properties.getRag().getIntent().setEnabled(false);
        properties.getRag().setDifyFilesUrl("http://files");
        RagApiService service = new RagApiService(
                metadataService,
                mock(QueryPlannerFacade.class),
                mock(RetrievalEngine.class),
                mock(LlmService.class),
                properties,
                memoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                client,
                runStore
        );
        List<RagApiService.StreamEvent> events = new ArrayList<>();

        service.streamAnswer(new ApiModels.RagAnswerRequest(
                "hello", "user-1", List.of("doc-1", "doc-2"), PlanType.AGENTIC_RAG, null, null, null, null),
                events::add);

        // answer.references 被 core 捕获，不再降级成 rag_progress。
        assertEquals(List.of("delta", "reference", "done"),
                events.stream().map(RagApiService.StreamEvent::name).toList());
        assertEquals(List.of(new ApiModels.Reference(
                        "cited.pdf",
                        "http://files/upload-2.pdf",
                        "doc-2",
                        "业务规则库"
                )),
                ((ApiModels.RagReferenceEvent) events.get(1).payload()).references());
    }

    @Test
    void streamAnswerFallsBackToAllCitationsWhenAgenticReferencesAreTruncated() throws Exception {
        AgenticRagClient client = mock(AgenticRagClient.class);
        AgenticRunStore runStore = mock(AgenticRunStore.class);
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        MetadataQueryService metadataService = mock(MetadataQueryService.class);
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        when(memoryService.resolve(any())).thenReturn(new ConversationMemoryContext("user-1", null));
        when(client.createRun(any(), any(), any())).thenReturn(runId);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<AgenticRagClient.AgenticEvent> consumer = invocation.getArgument(1);
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "1", "answer.references", mapper.readTree("""
                            {
                              "reference_count": 21,
                              "references_truncated": true,
                              "references": [
                                {"citation_no": 2, "document_id": "doc-2", "document_name": "cited.pdf"}
                              ]
                            }
                            """)));
            consumer.accept(new AgenticRagClient.AgenticEvent(
                    "2", "run.completed", mapper.readTree("{\"stage\":\"run\"}")));
            return null;
        }).when(client).streamEvents(any(), any(), any());
        when(client.getRun(runId, null)).thenReturn(new AgenticRagClient.AgenticRunResult(
                "completed",
                mapper.readTree("""
                        {
                          "answer": "answer",
                          "citations": [
                            {"document_id": "doc-1", "knowledge_base_id": "kb-1",
                             "metadata": {"document_name": "one.pdf"}},
                            {"document_id": "doc-2", "knowledge_base_id": "kb-1",
                             "metadata": {"document_name": "two.pdf"}}
                          ],
                          "partial": false,
                          "abstain": false
                        }
                        """),
                null
        ));
        when(metadataService.getDocumentMetas(List.of("doc-1", "doc-2"))).thenReturn(java.util.Map.of(
                "doc-1", new DocumentMeta("doc-1", "kb-1", "one.pdf", "upload-1", null),
                "doc-2", new DocumentMeta("doc-2", "kb-1", "two.pdf", "upload-2", null)
        ));
        when(metadataService.listKnowledgeBases(any(
                com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition.class))).thenReturn(List.of());
        AppProperties properties = new AppProperties();
        properties.getRag().getIntent().setEnabled(false);
        properties.getRag().setDifyFilesUrl("http://files");
        RagApiService service = new RagApiService(
                metadataService,
                mock(QueryPlannerFacade.class),
                mock(RetrievalEngine.class),
                mock(LlmService.class),
                properties,
                memoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                client,
                runStore
        );
        List<RagApiService.StreamEvent> events = new ArrayList<>();

        service.streamAnswer(new ApiModels.RagAnswerRequest(
                "hello", "user-1", List.of("doc-1", "doc-2"), PlanType.AGENTIC_RAG, null, null, null, null),
                events::add);

        assertEquals(List.of("reference", "done"), events.stream().map(RagApiService.StreamEvent::name).toList());
        assertEquals(List.of(
                        new ApiModels.Reference("one.pdf", "http://files/upload-1.pdf", "doc-1", "kb-1"),
                        new ApiModels.Reference("two.pdf", "http://files/upload-2.pdf", "doc-2", "kb-1")
                ),
                ((ApiModels.RagReferenceEvent) events.get(0).payload()).references());
    }
}
