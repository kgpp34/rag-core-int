package com.cffex.rag.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.application.RagApiService;

@WebMvcTest(RagController.class)
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagApiService ragApiService;

    @Test
    void search_routesToRagApiService() throws Exception {
        when(ragApiService.search(any())).thenReturn(new ApiModels.RetrievalResponse(
                "req-1",
                List.of(),
                Map.of("stage", "retrieval")
        ));

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "测试问题",
                                  "docIds": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.debugTrace.stage").value("retrieval"));

        verify(ragApiService).search(any(ApiModels.QueryRequest.class));
    }

    @Test
    void answer_routesToJsonEndpointWhenAcceptingApplicationJson() throws Exception {
        when(ragApiService.answer(any())).thenReturn(new ApiModels.RagAnswerResponse(
                "整理后的答案",
                List.of()
        ));

        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "请整理政策要点",
                                  "userId": "user-001",
                                  "docIds": ["doc-1"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value("整理后的答案"))
                .andExpect(jsonPath("$.references").isArray());

        verify(ragApiService).answer(any(ApiModels.RagAnswerRequest.class));
    }

    @Test
    void answer_routesToSseEndpointWhenAcceptingEventStream() throws Exception {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<RagApiService.StreamEvent> consumer = invocation.getArgument(1, Consumer.class);
            consumer.accept(new RagApiService.StreamEvent("rag_progress", new ApiModels.RagProgressEvent(
                    "retrieval-1",
                    1,
                    "trace-1",
                    Instant.parse("2026-06-03T00:00:00Z"),
                    "retrieval",
                    "started",
                    "正在检索知识库",
                    null,
                    Map.of("knowledgeBaseCount", 1)
            )));
            consumer.accept(new RagApiService.StreamEvent("delta", new ApiModels.RagAnswerStreamDelta("part-1")));
            consumer.accept(new RagApiService.StreamEvent("delta", new ApiModels.RagAnswerStreamDelta("part-2")));
            consumer.accept(new RagApiService.StreamEvent("done", new ApiModels.RagAnswerStreamDone(
                    "stop",
                    Map.of("llm_model_id", "llm-default")
            )));
            return null;
        }).when(ragApiService).streamAnswer(any(), any());

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "query": "请整理政策要点",
                                  "userId": "user-001",
                                  "docIds": ["doc-1"]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(1_000L);
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:rag_progress")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("part-1")))
                .andExpect(content().string(containsString("part-2")))
                .andExpect(content().string(containsString("stop")));

        verify(ragApiService).streamAnswer(any(ApiModels.RagAnswerRequest.class), any());
    }
}
