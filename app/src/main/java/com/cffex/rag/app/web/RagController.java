package com.cffex.rag.app.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.application.RagApiService;
import com.cffex.rag.common.exception.RagServiceException;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "RAG", description = "RAG 检索与问答接口")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private static final long SSE_TIMEOUT_MS = 0L;

    private final RagApiService ragApiService;

    public RagController(RagApiService ragApiService) {
        this.ragApiService = ragApiService;
    }

    @PostMapping(
            path = "/retrieval/search",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "检索知识片段",
            description = "根据查询条件返回召回片段与调试信息。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "检索成功",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.RetrievalResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数不合法",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部异常",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.ErrorResponse.class)
                    )
            )
    })
    public ApiModels.RetrievalResponse search(@Valid @RequestBody ApiModels.QueryRequest request) {
        return ragApiService.search(request);
    }

    @PostMapping(
            path = "/rag/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "RAG 问答",
            description = "默认返回完整答案；当请求头 Accept 为 text/event-stream 时，返回 SSE 流式结果。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "问答成功",
                    content = {
                            @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiModels.RagAnswerResponse.class)
                            ),
                            @Content(
                                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                    schema = @Schema(
                                            type = "string",
                                            description = "SSE 事件流，事件名包括 rag_progress、delta、done、error。"
                                    )
                            )
                    }
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数不合法",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部异常",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.ErrorResponse.class)
                    )
            )
    })
    public ApiModels.RagAnswerResponse answer(
            @Valid
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiModels.RagAnswerRequest.class),
                            examples = @ExampleObject(
                                    name = "多轮问答",
                                    value = """
                                            {
                                              "query": "请总结程序化交易异常报送流程。",
                                              "userId": "user-001",
                                              "docIds": [],
                                              "memory": {
                                                "conversationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                                              }
                                            }
                                            """
                            )
                    )
            )
            ApiModels.RagAnswerRequest request) {
        return ragApiService.answer(request);
    }

    @GetMapping(path = "/rag/agentic/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "查询 Agentic Run", description = "返回 core 持久化的 Run、结果及记忆写入状态。")
    public ApiModels.AgenticRunResponse getAgenticRun(@PathVariable UUID runId) {
        return ragApiService.getAgenticRun(runId);
    }

    @GetMapping(path = "/rag/agentic/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "查询会话的 Agentic Runs", description = "按创建时间倒序返回指定会话的 Runs。")
    public List<ApiModels.AgenticRunResponse> listAgenticRuns(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ragApiService.listAgenticRuns(conversationId, limit);
    }

    @PostMapping(
            path = "/rag/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Hidden
    public SseEmitter answerStream(
            @Valid @RequestBody ApiModels.RagAnswerRequest request,
            HttpServletResponse response
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        SseEventDispatcher dispatcher = new SseEventDispatcher(emitter, response);

        Thread.startVirtualThread(() -> {
            restoreMdc(capturedMdc);
            try {
                ragApiService.streamAnswer(request, dispatcher::send);
                dispatcher.complete();
            } catch (Exception ex) {
                sendErrorAndComplete(dispatcher, ex);
            } finally {
                MDC.clear();
            }
        });
        return emitter;
    }

    private void sendErrorAndComplete(SseEventDispatcher dispatcher, Exception ex) {
        String code = "INTERNAL_ERROR";
        String message = "流式服务异常，请稍后重试";
        if (ex instanceof RagServiceException rse) {
            code = rse.errorCode().name();
            message = rse.getMessage();
        } else if (ex instanceof UncheckedIOException) {
            code = "LLM_UNAVAILABLE";
            message = "流式连接中断";
        }
        log.error("流式问答异常，errorCode={}，message={}", code, message, ex);
        try {
            dispatcher.send(new RagApiService.StreamEvent(
                    "error",
                    new ApiModels.RagAnswerStreamError(code, message)
            ));
        } catch (Exception ignored) {
        }
        dispatcher.complete();
    }

    private static void restoreMdc(Map<String, String> capturedMdc) {
        if (capturedMdc == null || capturedMdc.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(capturedMdc);
    }

    /** 请求级串行 SSE 分发器，避免并发线程同时写入 SseEmitter。 */
    private static final class SseEventDispatcher {

        private final SseEmitter emitter;
        private final HttpServletResponse response;
        private boolean completed;

        private SseEventDispatcher(SseEmitter emitter, HttpServletResponse response) {
            this.emitter = emitter;
            this.response = response;
        }

        private synchronized void send(RagApiService.StreamEvent event) {
            if (completed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event.name())
                        .data(event.payload()));
                response.flushBuffer();
            } catch (IOException ex) {
                completed = true;
                throw new UncheckedIOException(ex);
            }
        }

        private synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            emitter.complete();
        }
    }
}
