package com.cffex.rag.app.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.domain.llm.LlmMessage;
import com.cffex.rag.common.domain.llm.LlmMessageRole;
import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.llm.LlmResponse;
import com.cffex.rag.common.domain.llm.LlmStreamChunk;
import com.cffex.rag.common.domain.memory.ConversationMemoryContext;
import com.cffex.rag.common.domain.memory.ConversationMessage;
import com.cffex.rag.common.domain.memory.ConversationMemoryRequest;
import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.SegmentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;
import com.cffex.rag.common.domain.retrieval.RetrievalResult;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.domain.terminology.TerminologyReplacement;
import com.cffex.rag.common.domain.terminology.TerminologyResult;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.common.service.LlmService;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.common.service.QueryPlannerFacade;
import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.common.service.RagProcessEventPublisher.RagProcessStage;
import com.cffex.rag.common.service.RagProcessEventPublisher.StageHandle;
import com.cffex.rag.common.service.RetrievalEngine;
import com.cffex.rag.common.service.ConversationMemoryService;
import com.cffex.rag.common.service.TerminologyService;
import com.cffex.rag.trace.application.NoOpTraceRecorder;
import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RAG API 应用服务。
 *
 * <p>负责串联查询规划、检索执行和 LLM 生成三段主流程，
 * 并把领域对象转换成对外接口需要的响应结构。
 */
@Service
public class RagApiService {

    private static final String CONFLUENCE_SOURCE_TYPE = "confluence";

    private static final Logger log = LoggerFactory.getLogger(RagApiService.class);
    private static final ObjectMapper QUERY_REWRITE_OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SUMMARY_TRACE_EVENTS = Set.of(
            "rag.request.received",
            "intent.classified",
            "intent.direct_answer",
            "query_rewrite.summary",
            "retrieval.summary",
            "answer_generation.summary",
            "answer_generation.references"
    );
    private static final Pattern REFERENCE_HEADING_PATTERN = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*参考资料\\s*[:：]?\\s*$");
    private static final String UNRELATED_ANSWER_MESSAGE = "当前检索结果与问题明显不相关，无法基于参考资料回答";
    private static final int REFERENCE_SECTION_DETECTION_GUARD_CHARS = 24;

    private final MetadataQueryService metadataQueryService;
    private final QueryPlannerFacade queryPlannerFacade;
    private final RetrievalEngine retrievalEngine;
    private final LlmService llmService;
    private final AppProperties appProperties;
    private final ConversationMemoryService conversationMemoryService;
    private final AgenticRagClient agenticRagClient;
    private final AgenticRunStore agenticRunStore;
    private final TraceRecorder traceRecorder;
    private final TraceProperties traceProperties;
    private final QuestionIntentService questionIntentService;
    private final TerminologyService terminologyService;

    @Autowired
    public RagApiService(
            MetadataQueryService metadataQueryService,
            QueryPlannerFacade queryPlannerFacade,
            RetrievalEngine retrievalEngine,
            LlmService llmService,
            AppProperties appProperties,
            ConversationMemoryService conversationMemoryService,
            TraceRecorder traceRecorder,
            TraceProperties traceProperties,
            AgenticRagClient agenticRagClient,
            AgenticRunStore agenticRunStore,
            TerminologyService terminologyService
    ) {
        this.metadataQueryService = Objects.requireNonNull(metadataQueryService);
        this.queryPlannerFacade = Objects.requireNonNull(queryPlannerFacade);
        this.retrievalEngine = Objects.requireNonNull(retrievalEngine);
        this.llmService = Objects.requireNonNull(llmService);
        this.appProperties = Objects.requireNonNull(appProperties);
        this.conversationMemoryService = Objects.requireNonNull(conversationMemoryService);
        this.agenticRagClient = Objects.requireNonNull(agenticRagClient);
        this.agenticRunStore = Objects.requireNonNull(agenticRunStore);
        this.traceRecorder = Objects.requireNonNull(traceRecorder);
        this.traceProperties = Objects.requireNonNull(traceProperties);
        this.questionIntentService = new QuestionIntentService(llmService, metadataQueryService, appProperties);
        this.terminologyService = Objects.requireNonNull(terminologyService);
    }

    RagApiService(
            MetadataQueryService metadataQueryService,
            QueryPlannerFacade queryPlannerFacade,
            RetrievalEngine retrievalEngine,
            LlmService llmService,
            AppProperties appProperties,
            ConversationMemoryService conversationMemoryService
    ) {
        this(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService,
                new NoOpTraceRecorder(),
                new TraceProperties(),
                new DisabledAgenticRagClient(),
                new DisabledAgenticRunStore(),
                TerminologyService.DISABLED
        );
    }

    RagApiService(
            MetadataQueryService metadataQueryService,
            QueryPlannerFacade queryPlannerFacade,
            RetrievalEngine retrievalEngine,
            LlmService llmService,
            AppProperties appProperties,
            ConversationMemoryService conversationMemoryService,
            TraceRecorder traceRecorder,
            TraceProperties traceProperties,
            AgenticRagClient agenticRagClient,
            AgenticRunStore agenticRunStore
    ) {
        this(
                metadataQueryService,
                queryPlannerFacade,
                retrievalEngine,
                llmService,
                appProperties,
                conversationMemoryService,
                traceRecorder,
                traceProperties,
                agenticRagClient,
                agenticRunStore,
                TerminologyService.DISABLED
        );
    }

    public ApiModels.RetrievalResponse search(ApiModels.QueryRequest request) {
        long start = System.nanoTime();
        recordTrace("request", "rag.request.received", requestReceivedPayload(
                "search",
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                queryRewriteEnabled(request),
                null,
                false
        ));
        log.info("开始处理检索请求，问题长度={}，文档数={}，计划类型={}，检索模式={}，queryRewrite={}",
                request.query().length(),
                safeList(request.docIds()).size(),
                request.planType(),
                request.retrievalMode() == null ? "(未传，用知识库默认)" : request.retrievalMode(),
                queryRewriteEnabled(request));
        try {
            ExecutionPlan executionPlan = planSearch(request);
            RetrievalPlan plan = executionPlan.primaryRetrievalPlan();
            RetrievalExecutionOutcome outcome = executeRetrievalWithRewrite(
                    request.query(),
                    queryRewriteEnabled(request),
                    queryRewritePrompt(request),
                    plan,
                    RagProcessEventPublisher.NO_OP,
                    null
            );
            RetrievalResult result = outcome.result();
            log.info("检索请求处理完成，请求ID={}，结果块数={}，检索子计划数={}，召回规格数={}，排序策略={}",
                    result.requestId(),
                    result.chunks().size(),
                    executionPlan.retrievalPlans().size(),
                    plan.recallSpecs().size(),
                    plan.rankingSpec().getClass().getSimpleName());
            return toRetrievalResponse(result);
        } catch (RuntimeException ex) {
            recordTrace("request", "rag.request.failed", Map.of(
                    "endpointType", "search",
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw ex;
        }
    }

    public ApiModels.AgenticRunResponse getAgenticRun(UUID runId) {
        return agenticRunStore.findByRunId(runId)
                .map(this::toAgenticRunResponse)
                .orElseThrow(() -> new RagServiceException(
                        RagErrorCode.AGENTIC_RUN_NOT_FOUND,
                        "Agentic Run 不存在: " + runId
                ));
    }

    public List<ApiModels.AgenticRunResponse> listAgenticRuns(String conversationId, int limit) {
        String normalizedConversationId = normalizeStatic(conversationId);
        if (normalizedConversationId == null) {
            throw new RagServiceException(RagErrorCode.INVALID_REQUEST, "conversationId 不能为空");
        }
        if (limit < 1 || limit > 200) {
            throw new RagServiceException(RagErrorCode.INVALID_REQUEST, "limit 必须在 1 到 200 之间");
        }
        return agenticRunStore.findByConversationId(normalizedConversationId, limit).stream()
                .map(this::toAgenticRunResponse)
                .toList();
    }

    private ApiModels.AgenticRunResponse toAgenticRunResponse(AgenticRun run) {
        return new ApiModels.AgenticRunResponse(
                run.runId(),
                run.requestId(),
                run.conversationId(),
                run.userId(),
                run.status(),
                run.query(),
                run.output(),
                run.error(),
                run.memoryStatus(),
                run.memoryAttempts(),
                run.memoryError(),
                run.createdAt(),
                run.updatedAt(),
                run.completedAt()
        );
    }

    public ApiModels.RagAnswerResponse answer(ApiModels.RagAnswerRequest request) {
        if (isCapabilityIntroduction(request)) {
            String answer = capabilityIntroduction(request);
            return new ApiModels.RagAnswerResponse(answer, List.of());
        }
        if (request.planType() == com.cffex.rag.common.domain.query.PlanType.AGENTIC_RAG) {
            AgenticExecutionResult result = executeAgenticAnswer(request, event -> { }, false);
            return new ApiModels.RagAnswerResponse(result.answer(), result.references());
        }
        long start = System.nanoTime();
        recordTrace("request", "rag.request.received", requestReceivedPayload(
                "answer",
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                queryRewriteEnabled(request),
                request.memory() == null ? null : request.memory().conversationId(),
                false
        ));
        log.info("开始处理问答请求，问题长度={}，文档数={}，计划类型={}，流式={}",
                request.query().length(),
                safeList(request.docIds()).size(),
                request.planType(),
                false);
        try {
            PreparedAnswer preparedAnswer = prepareAnswer(request, RagProcessEventPublisher.NO_OP);
            log.info("问答生成开始，请求ID={}，结果块数={}，模型ID={}，流式={}",
                    preparedAnswer.retrieval().requestId(),
                    preparedAnswer.retrieval().chunks().size(),
                    preparedAnswer.llmModel().modelId(),
                    false);
            LlmResponse llmResponse = generateAnswer(preparedAnswer.llmRequest(), preparedAnswer.referenceSources());
            log.info("问答请求处理完成，请求ID={}，结束原因={}，结果块数={}",
                    preparedAnswer.retrieval().requestId(),
                    llmResponse.finishReason(),
                    preparedAnswer.retrieval().chunks().size());
            return toRagAnswerResponse(llmResponse.content(), preparedAnswer);
        } catch (RuntimeException ex) {
            recordTrace("request", "rag.request.failed", Map.of(
                    "endpointType", "answer",
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw ex;
        }
    }

    public void streamAnswer(
            ApiModels.RagAnswerRequest request,
            Consumer<StreamEvent> eventConsumer
    ) {
        if (isCapabilityIntroduction(request)) {
            String answer = capabilityIntroduction(request);
            eventConsumer.accept(new StreamEvent("delta", new ApiModels.RagAnswerStreamDelta(answer)));
            eventConsumer.accept(new StreamEvent("done", new ApiModels.RagAnswerStreamDone("stop", Map.of())));
            return;
        }
        if (request.planType() == com.cffex.rag.common.domain.query.PlanType.AGENTIC_RAG) {
            streamAgenticAnswer(request, eventConsumer);
            return;
        }
        long start = System.nanoTime();
        recordTrace("request", "rag.request.received", requestReceivedPayload(
                "answer",
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                queryRewriteEnabled(request),
                request.memory() == null ? null : request.memory().conversationId(),
                true
        ));
        log.info("开始处理问答请求，问题长度={}，文档数={}，计划类型={}，流式={}",
                request.query().length(),
                safeList(request.docIds()).size(),
                request.planType(),
                true);
        RagProcessEventPublisher eventPublisher = new SseRagProcessEventPublisher(eventConsumer);
        PreparedAnswer preparedAnswer = prepareAnswer(request, eventPublisher);
        log.info("问答生成开始，请求ID={}，结果块数={}，模型ID={}，流式={}",
                preparedAnswer.retrieval().requestId(),
                preparedAnswer.retrieval().chunks().size(),
                preparedAnswer.llmModel().modelId(),
                true);

        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<String> finishReasonHolder = new AtomicReference<>();
        AtomicReference<Map<String, Object>> metadataHolder = new AtomicReference<>(Map.of());
        AtomicBoolean firstTokenEmitted = new AtomicBoolean();
        AtomicReference<Long> firstTokenMsHolder = new AtomicReference<>();
        ReferenceSectionSuppressor referenceSectionSuppressor = new ReferenceSectionSuppressor();
        long generationStart = System.nanoTime();
        StageHandle generationHandle = eventPublisher.start(RagProcessStage.ANSWER_GENERATION, Map.of());
        recordTrace("answer_generation", "answer_generation.started", Map.of(
                "modelId", preparedAnswer.llmModel().modelId(),
                "messageCount", preparedAnswer.llmRequest().messages().size(),
                "referenceCount", preparedAnswer.retrieval().chunks().size(),
                "stream", true
        ));
        try {
            llmService.streamGenerate(preparedAnswer.llmRequest(), chunk -> {
                handleStreamChunk(
                        chunk,
                        answerBuilder,
                        finishReasonHolder,
                        metadataHolder,
                        preparedAnswer,
                        eventConsumer,
                        eventPublisher,
                        generationHandle,
                        firstTokenEmitted,
                        firstTokenMsHolder,
                        referenceSectionSuppressor,
                        generationStart
                );
            });
        } catch (RagServiceException ex) {
            eventPublisher.fail(generationHandle, ex.errorCode().name());
            recordTrace("answer_generation", "answer_generation.failed", Map.of(
                    "errorCode", ex.errorCode().name(),
                    "message", summarize(ex)
            ));
            recordTrace("request", "rag.request.failed", Map.of(
                    "endpointType", "answer",
                    "error", summarize(ex),
                    "totalMs", durationMs(start),
                    "stream", true
            ));
            throw ex;
        } catch (RuntimeException ex) {
            eventPublisher.fail(generationHandle, RagErrorCode.LLM_UNAVAILABLE.name());
            recordTrace("answer_generation", "answer_generation.failed", Map.of(
                    "errorCode", RagErrorCode.LLM_UNAVAILABLE.name(),
                    "message", summarize(ex)
            ));
            recordTrace("request", "rag.request.failed", Map.of(
                    "endpointType", "answer",
                    "error", summarize(ex),
                    "totalMs", durationMs(start),
                    "stream", true
            ));
            throw new RagServiceException(
                    RagErrorCode.LLM_UNAVAILABLE,
                    "流式答案生成失败: " + summarize(ex),
                    ex
            );
        }

        if (finishReasonHolder.get() == null) {
            log.info("流式问答结束，但未收到显式结束块，请求ID={}，结果块数={}",
                    preparedAnswer.retrieval().requestId(),
                    preparedAnswer.retrieval().chunks().size());
            flushStreamAnswerTail(answerBuilder, referenceSectionSuppressor, preparedAnswer, eventConsumer);
            eventPublisher.complete(generationHandle, answerGenerationDetails(null, metadataHolder.get()));
            eventConsumer.accept(new StreamEvent("done", new ApiModels.RagAnswerStreamDone(
                    null,
                    Map.of()
            )));
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("requestId", preparedAnswer.retrieval().requestId());
            tracePayload.put("model", preparedAnswer.llmRequest().modelEndpoint().model());
            tracePayload.put("finishReason", "");
            tracePayload.put("answerLength", answerBuilder.length());
            tracePayload.put("answerContent", answerBuilder.toString());
            appendLlmRequestTraceFields(tracePayload, preparedAnswer.llmRequest());
            tracePayload.put("firstTokenMs", Objects.requireNonNullElse(firstTokenMsHolder.get(), -1L));
            tracePayload.put("elapsedMs", durationMs(generationStart));
            tracePayload.put("stream", true);
            recordTrace("answer_generation", "answer_generation.summary", tracePayload);
            recordAnswerReferences(answerBuilder.toString(), preparedAnswer.referenceSources());
        }
    }

    private boolean isCapabilityIntroduction(ApiModels.RagAnswerRequest request) {
        long start = System.nanoTime();
        QuestionIntentService.Intent intent = questionIntentService.classify(
                request.query(), () -> toEndpoint(resolveLlmModel(appProperties.getRag().getIntent().getModel())));
        if (appProperties.getRag().getIntent().isEnabled()) {
            recordTrace("intent", "intent.classified", Map.of("intent", intent.name(), "elapsedMs", durationMs(start)));
        }
        return intent == QuestionIntentService.Intent.CAPABILITY_INTRO;
    }

    private String capabilityIntroduction(ApiModels.RagAnswerRequest request) {
        String answer = questionIntentService.introduction(safeList(request.docIds()));
        ApiModels.MemoryConfig memory = request.memory();
        if (memory != null) {
            ConversationMemoryContext context = conversationMemoryService.resolve(new ConversationMemoryRequest(
                    request.userId(), true, memory.conversationId()));
            if (context.conversationId() != null) {
                conversationMemoryService.appendExchange(context.conversationId(), request.query(), answer);
            }
        }
        recordTrace("intent", "intent.direct_answer", Map.of("answerLength", answer.length()));
        return answer;
    }

    private void streamAgenticAnswer(
            ApiModels.RagAnswerRequest request,
            Consumer<StreamEvent> eventConsumer
    ) {
        executeAgenticAnswer(request, eventConsumer, true);
    }

    private AgenticExecutionResult executeAgenticAnswer(
            ApiModels.RagAnswerRequest request,
            Consumer<StreamEvent> eventConsumer,
            boolean stream
    ) {
        if (!appProperties.getRag().getAgentic().isEnabled()) {
            throw new RagServiceException(RagErrorCode.PLAN_UNSUPPORTED, "Agentic RAG 未启用");
        }
        long start = System.nanoTime();
        recordTrace("request", "rag.request.received", requestReceivedPayload(
                "answer",
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                queryRewriteEnabled(request),
                request.memory() == null ? null : request.memory().conversationId(),
                stream
        ));
        String requestId = MDC.get("traceId");
        ApiModels.MemoryConfig memory = request.memory();
        ConversationMemoryContext memoryContext = conversationMemoryService.resolve(new ConversationMemoryRequest(
                request.userId(),
                memory != null,
                memory != null ? memory.conversationId() : null
        ));
        List<AgenticRagClient.AgenticMessage> messages = new ArrayList<>();
        if (memoryContext.conversationId() != null) {
            for (ConversationMessage message : conversationMemoryService.recentMessages(
                    memoryContext.conversationId(),
                    appProperties.getRag().getAgentic().getMemoryMessageLimit()
            )) {
                messages.add(new AgenticRagClient.AgenticMessage(message.role(), message.content()));
            }
        }
        messages.add(new AgenticRagClient.AgenticMessage("user", request.query()));
        UUID runId = agenticRagClient.createRun(List.copyOf(messages), safeList(request.docIds()), requestId);
        log.info("Agentic Run 已创建，runId={}，requestId={}，conversationId={}，historyCount={}，stream={}",
                runId, requestId, memoryContext.conversationId(), Math.max(0, messages.size() - 1), stream);
        agenticRunStore.create(
                runId,
                requestId,
                memoryContext.conversationId(),
                memoryContext.userId(),
                request.query()
        );
        StringBuilder streamedAnswer = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean terminalSeen = new AtomicBoolean();
        AtomicBoolean terminalRecorded = new AtomicBoolean();
        AtomicLong progressSequence = new AtomicLong();
        AtomicReference<AgenticExecutionResult> resultHolder = new AtomicReference<>();
        AtomicReference<JsonNode> agenticReferences = new AtomicReference<>();
        Consumer<AgenticRagClient.AgenticRunResult> completeRun = result -> {
            if (completed.get()) {
                return;
            }
            JsonNode output = result.output();
            String finalAnswer = output != null
                    ? output.path("answer").asText(streamedAnswer.toString())
                    : streamedAnswer.toString();
            agenticRunStore.markCompleted(runId, output);
            terminalRecorded.set(true);
            writeAgenticMemory(runId, memoryContext.conversationId(), request.query(), finalAnswer);
            // 过滤依据优先取 answer.references 事件，事件丢失（如 SSE 提前断开走
            // getRun 对账）时退回运行输出里的 cited_document_ids，都不存在才回退全部 citations。
            Set<String> citedDocumentIds = citedDocumentIds(agenticReferences.get());
            if (citedDocumentIds == null) {
                citedDocumentIds = citedDocumentIds(output);
            }
            List<ApiModels.Reference> references = buildAgenticReferences(output, citedDocumentIds);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", runId.toString());
            metadata.put("status", result.status());
            metadata.put("references", references);
            if (output != null && output.isObject()) {
                metadata.put("citations", output.path("citations"));
                metadata.put("partial", output.path("partial").asBoolean(false));
                metadata.put("abstain", output.path("abstain").asBoolean(false));
            }
            eventConsumer.accept(new StreamEvent(
                    "reference",
                    new ApiModels.RagReferenceEvent(references)
            ));
            eventConsumer.accept(new StreamEvent(
                    "done",
                    new ApiModels.RagAnswerStreamDone("stop", metadata)
            ));
            resultHolder.set(new AgenticExecutionResult(
                    runId,
                    finalAnswer,
                    references,
                    output
            ));
            recordTrace("answer_generation", "agentic_run.completed", Map.of(
                    "runId", runId.toString(),
                    "answerLength", finalAnswer.length(),
                    "referenceCount", references.size(),
                    "partial", output != null && output.path("partial").asBoolean(false),
                    "abstain", output != null && output.path("abstain").asBoolean(false),
                    "elapsedMs", durationMs(start),
                    "stream", stream
            ));
            log.info("Agentic Run 已完成，runId={}，answerLength={}，referenceCount={}，elapsedMs={}，stream={}",
                    runId, finalAnswer.length(), references.size(), durationMs(start), stream);
            completed.set(true);
        };
        try {
            agenticRagClient.streamEvents(runId, event -> {
                switch (event.eventType()) {
                    case "answer.delta" -> {
                        String delta = event.data().path("delta").asText("");
                        if (!delta.isEmpty()) {
                            streamedAnswer.append(delta);
                            eventConsumer.accept(new StreamEvent(
                                    "delta",
                                    new ApiModels.RagAnswerStreamDelta(delta)
                            ));
                        }
                    }
                    case "answer.references" -> {
                        // agentic-int 已经算好"正文实际引用到的来源"，这里只捕获，
                        // 统一在 run.completed 时转换成 core 的 Reference 格式。
                        agenticReferences.set(event.data());
                    }
                    case "run.completed" -> {
                        terminalSeen.set(true);
                        AgenticRagClient.AgenticRunResult result = agenticRagClient.getRun(runId, requestId);
                        completeRun.accept(result);
                    }
                    case "run.failed" -> {
                        terminalSeen.set(true);
                        agenticRunStore.markFailed(runId, event.data());
                        terminalRecorded.set(true);
                        String message = event.data().path("message").asText("Agentic Run 执行失败");
                        throw new RagServiceException(RagErrorCode.AGENTIC_RUN_FAILED, message);
                    }
                    case "run.cancelled" -> {
                        terminalSeen.set(true);
                        agenticRunStore.markCancelled(runId);
                        terminalRecorded.set(true);
                        throw new RagServiceException(RagErrorCode.AGENTIC_RUN_FAILED, "Agentic Run 已取消");
                    }
                    case "run.started" -> {
                        agenticRunStore.markRunning(runId);
                        eventConsumer.accept(new StreamEvent(
                                "rag_progress",
                                toAgenticProgressEvent(event, progressSequence.incrementAndGet())
                        ));
                    }
                    default -> eventConsumer.accept(new StreamEvent(
                            "rag_progress",
                            toAgenticProgressEvent(event, progressSequence.incrementAndGet())
                    ));
                }
            }, requestId);
            if (!completed.get()) {
                AgenticRagClient.AgenticRunResult result = agenticRagClient.getRun(runId, requestId);
                String status = result.status() == null ? "" : result.status().toLowerCase(Locale.ROOT);
                switch (status) {
                    case "completed" -> {
                        terminalSeen.set(true);
                        log.warn("Agentic SSE 未收到 run.completed，使用运行状态兜底，runId={}，requestId={}",
                                runId, requestId);
                        completeRun.accept(result);
                    }
                    case "failed" -> {
                        terminalSeen.set(true);
                        agenticRunStore.markFailed(runId, result.error());
                        terminalRecorded.set(true);
                        String message = result.error() == null
                                ? "Agentic Run 执行失败"
                                : result.error().path("message").asText("Agentic Run 执行失败");
                        throw new RagServiceException(RagErrorCode.AGENTIC_RUN_FAILED, message);
                    }
                    case "cancelled" -> {
                        terminalSeen.set(true);
                        agenticRunStore.markCancelled(runId);
                        terminalRecorded.set(true);
                        throw new RagServiceException(RagErrorCode.AGENTIC_RUN_FAILED, "Agentic Run 已取消");
                    }
                    default -> throw new RagServiceException(RagErrorCode.AGENTIC_PROTOCOL_ERROR,
                            "Agentic SSE 在完成事件前结束，当前运行状态=" + status);
                }
            }
        } catch (RuntimeException ex) {
            if (!terminalSeen.get() && !terminalRecorded.get()
                    && appProperties.getRag().getAgentic().isCancelOnDisconnect()) {
                agenticRagClient.cancelRun(runId, requestId);
            }
            recordTrace("request", "rag.request.failed", Map.of(
                    "endpointType", "answer",
                    "runId", runId.toString(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start),
                    "stream", stream
            ));
            throw ex;
        }
        return resultHolder.get();
    }

    private void writeAgenticMemory(
            UUID runId,
            String conversationId,
            String query,
            String answer
    ) {
        if (conversationId == null || !agenticRunStore.claimMemoryWrite(runId, java.time.Instant.now())) {
            return;
        }
        try {
            conversationMemoryService.appendExchangeOnce(runId.toString(), conversationId, query, answer);
            agenticRunStore.markMemoryWritten(runId);
        } catch (RuntimeException ex) {
            agenticRunStore.markMemoryWriteFailed(runId, summarize(ex));
            throw ex;
        }
    }

    /**
     * 把 agentic-int 的 citations 转换成 core 的 {@link ApiModels.Reference}（文件级去重）。
     *
     * <p>当 agentic-int 提供了 {@code answer.references} 事件（正文实际引用到的来源）时，只保留
     * 这些文档；事件缺失或内容被截断时回退为全部 citations，避免新老版本混布时丢引用。
     * URL 与数据集名称仍由 core 自己生成，对外契约保持不变。
     * 每个文档的 {@code chunkIds} 从 citations 的 {@code chunk_id} 按出现顺序去重聚合。
     */
    private List<ApiModels.Reference> buildAgenticReferences(JsonNode output, Set<String> citedDocumentIds) {
        JsonNode citations = output == null ? null : output.path("citations");
        if (citations == null || !citations.isArray() || citations.isEmpty()) {
            return List.of();
        }
        List<String> documentIds = new ArrayList<>();
        citations.forEach(citation -> {
            String documentId = normalizeStatic(citation.path("document_id").asText(null));
            if (documentId == null || documentIds.contains(documentId)) {
                return;
            }
            if (citedDocumentIds != null && !citedDocumentIds.contains(documentId)) {
                return;
            }
            documentIds.add(documentId);
        });
        if (documentIds.isEmpty()) {
            return List.of();
        }
        Map<String, DocumentMeta> documentMetas = metadataQueryService.getDocumentMetas(documentIds);
        Map<String, String> datasetNames = resolveDatasetNames();
        Map<String, List<String>> chunkIdsByDocument =
                resolveAgenticChunkIdsByDocument(citations, citedDocumentIds);
        Map<String, ApiModels.Reference> references = new LinkedHashMap<>();
        citations.forEach(citation -> {
            String documentId = normalizeStatic(citation.path("document_id").asText(null));
            if (citedDocumentIds != null && !citedDocumentIds.contains(documentId)) {
                return;
            }
            DocumentMeta documentMeta = documentMetas.get(documentId);
            JsonNode metadata = citation.path("metadata");
            String fileName = firstNonBlank(
                    firstNonBlank(jsonText(metadata, "document_name"), jsonText(metadata, "file_name")),
                    documentMeta == null ? null : documentMeta.name()
            );
            if (fileName == null) {
                return;
            }
            String uploadFileId = firstNonBlank(
                    jsonText(metadata, "upload_file_id"),
                    documentMeta == null ? null : documentMeta.uploadFileId()
            );
            String path = buildFilePath(
                    appProperties.getRag().getDifyFilesUrl(),
                    uploadFileId,
                    documentMeta == null ? null : documentMeta.uploadFileKey(),
                    fileName
            );
            path = resolveReferencePath(documentMeta, path);
            String knowledgeBaseId = firstNonBlank(
                    firstNonBlank(jsonText(metadata, "knowledge_base_id"), jsonText(citation, "knowledge_base_id")),
                    documentMeta == null ? null : documentMeta.knowledgeBaseId()
            );
            String datasetName = datasetNameOrId(datasetNames, knowledgeBaseId);
            references.putIfAbsent(
                    documentId + '\u001f' + fileName,
                    new ApiModels.Reference(
                            fileName,
                            path,
                            documentId,
                            datasetName,
                            chunkIdsByDocument.getOrDefault(documentId, List.of())
                    )
            );
        });
        return List.copyOf(references.values());
    }

    /**
     * 按 documentId 聚合 citations 中每条引用的对外 chunk 标识。
     *
     * <p>引用条目的 metadata JSON 里带有 dify 分段索引节点 ID（{@code doc_id}）；
     * 先批量到 metacache 的 document_segments 缓存里换取分段 position，
     * 未命中时退回 index_node_id 本身，再退回检索链路的原始 chunk 标识。
     */
    private Map<String, List<String>> resolveAgenticChunkIdsByDocument(JsonNode citations, Set<String> citedDocumentIds) {
        Map<String, List<String>> refsByDocument = new LinkedHashMap<>();
        citations.forEach(citation -> {
            String documentId = normalizeStatic(citation.path("document_id").asText(null));
            if (documentId == null || (citedDocumentIds != null && !citedDocumentIds.contains(documentId))) {
                return;
            }
            String indexNodeId = jsonText(citation.path("metadata"), "doc_id");
            String rawChunkId = jsonText(citation, "chunk_id");
            String ref = indexNodeId != null ? indexNodeId : rawChunkId;
            if (ref == null) {
                return;
            }
            List<String> refs = refsByDocument.computeIfAbsent(documentId, ignored -> new ArrayList<>());
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        });
        if (refsByDocument.isEmpty()) {
            return Map.of();
        }
        Map<String, String> positions = resolveSegmentPositions(refsByDocument.values().stream()
                .flatMap(List::stream)
                .toList());
        Map<String, List<String>> chunkIdsByDocument = new LinkedHashMap<>();
        refsByDocument.forEach((documentId, refs) -> {
            List<String> chunkIds = chunkIdsForRefs(refs, positions);
            if (!chunkIds.isEmpty()) {
                chunkIdsByDocument.put(documentId, chunkIds);
            }
        });
        return chunkIdsByDocument;
    }

    /**
     * 批量把 dify 分段索引节点 ID 解析成文档内分段序号（position）。
     * 缓存未命中的 ID 不出现在返回 Map 中，由调用方决定回退值。
     */
    private Map<String, String> resolveSegmentPositions(Collection<String> indexNodeIds) {
        List<String> uniqueIds = List.copyOf(new LinkedHashSet<>(indexNodeIds));
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SegmentMeta> metas = metadataQueryService.getSegmentMetas(uniqueIds);
        if (metas == null || metas.isEmpty()) {
            return Map.of();
        }
        Map<String, String> positions = new LinkedHashMap<>();
        metas.forEach((indexNodeId, meta) -> positions.put(indexNodeId, String.valueOf(meta.position())));
        return positions;
    }

    /** 按引用出现顺序把索引节点 ID 换成对外 chunk 标识并去重。 */
    private static List<String> chunkIdsForRefs(List<String> refs, Map<String, String> positions) {
        List<String> chunkIds = new ArrayList<>();
        for (String ref : refs) {
            String chunkId = positions.getOrDefault(ref, ref);
            if (!chunkIds.contains(chunkId)) {
                chunkIds.add(chunkId);
            }
        }
        return List.copyOf(chunkIds);
    }

    /**
     * 读取"正文实际引用到的"文档集合。事件与运行输出（{@code run.output}）统一携带
     * 未截断的 {@code cited_document_ids}——字段存在即为权威依据（空数组表示正文零引用，
     * 参考列表必须为空，与正文严格一致）；旧版本只有展示用 {@code references} 列表
     * （超过 20 条被截断），此时返回 {@code null} 表示不过滤（回退为全部 citations）。
     *
     * @return 需要保留的 documentId 集合（可为空集合）；{@code null} 表示无过滤依据
     */
    private static Set<String> citedDocumentIds(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode citedIdsNode = node.path("cited_document_ids");
        if (citedIdsNode.isArray()) {
            return stringSet(citedIdsNode);
        }
        if (node.path("references_truncated").asBoolean(false)) {
            return null;
        }
        JsonNode references = node.path("references");
        if (!references.isArray() || references.isEmpty()) {
            return null;
        }
        Set<String> documentIds = new LinkedHashSet<>();
        references.forEach(reference -> {
            String documentId = normalizeStatic(reference.path("document_id").asText(null));
            if (documentId != null) {
                documentIds.add(documentId);
            }
        });
        return documentIds.isEmpty() ? null : documentIds;
    }

    private static Set<String> stringSet(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        array.forEach(item -> {
            String value = item.isTextual() ? normalizeStatic(item.asText()) : null;
            if (value != null) {
                values.add(value);
            }
        });
        return values;
    }

    private static String jsonText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : normalizeStatic(value.asText());
    }

    private ApiModels.RagProgressEvent toAgenticProgressEvent(
            AgenticRagClient.AgenticEvent event,
            long sequence
    ) {
        String stage = event.data().path("stage").asText("agentic");
        String status = event.data().path("status").asText("milestone");
        String title = event.data().path("action").asText(event.eventType());
        Long elapsedMs = event.data().has("duration_ms")
                ? event.data().path("duration_ms").asLong()
                : null;
        Map<String, Object> details = new LinkedHashMap<>();
        event.data().fields().forEachRemaining(entry -> details.put(entry.getKey(), entry.getValue()));
        return new ApiModels.RagProgressEvent(
                "agentic-" + (event.id() == null ? UUID.randomUUID() : event.id()),
                sequence,
                MDC.get("traceId"),
                java.time.Instant.now(),
                stage,
                status,
                title,
                elapsedMs,
                details
        );
    }

    /** 将检索 API 请求转换为查询规划请求。 */
    private QueryPlanRequest toQueryPlanRequest(ApiModels.QueryRequest request) {
        return new QueryPlanRequest(
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                request.systemPrompt(),
                request.retrievalMode()
        );
    }

    /** 将检索 API 请求转换为可直连 planner 的检索上下文。 */
    private RetrievalContext toRetrievalContext(ApiModels.QueryRequest request) {
        List<String> knowledgeBaseIds = resolveKnowledgeBaseIds(safeList(request.docIds()));
        Double scoreThreshold = tuningScoreThreshold(request);
        return new RetrievalContext(
                "app-api",
                request.query(),
                knowledgeBaseIds,
                safeList(request.docIds()),
                null,
                null,
                request.retrievalMode(),
                positiveOrZero(tuningTopK(request)),
                positiveOrZero(tuningCandidateK(request)),
                !Boolean.FALSE.equals(tuningRerankEnabled(request)),
                scoreThreshold != null,
                scoreThreshold == null ? 0.0d : scoreThreshold,
                Map.of(),
                Map.of()
        );
    }

    /** 将问答 API 请求转换为查询规划请求。 */
    private QueryPlanRequest toQueryPlanRequest(ApiModels.RagAnswerRequest request) {
        return new QueryPlanRequest(
                request.query(),
                safeList(request.docIds()),
                request.planType(),
                request.systemPrompt()
        );
    }

    /** 将问答 API 请求转换为可直连 planner 的检索上下文。 */
    private RetrievalContext toRetrievalContext(ApiModels.RagAnswerRequest request) {
        List<String> knowledgeBaseIds = resolveKnowledgeBaseIds(safeList(request.docIds()));
        Double scoreThreshold = tuningScoreThreshold(request);
        return new RetrievalContext(
                "app-api",
                request.query(),
                knowledgeBaseIds,
                safeList(request.docIds()),
                null,
                null,
                null,
                positiveOrZero(tuningTopK(request)),
                positiveOrZero(tuningCandidateK(request)),
                !Boolean.FALSE.equals(tuningRerankEnabled(request)),
                scoreThreshold != null,
                scoreThreshold == null ? 0.0d : scoreThreshold,
                Map.of(),
                Map.of()
        );
    }

    /** 统一处理可空列表，避免下游链路重复判空。 */
    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static Integer tuningTopK(ApiModels.QueryRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().topK() : null;
    }

    private static Integer tuningCandidateK(ApiModels.QueryRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().candidateK() : null;
    }

    private static Double tuningScoreThreshold(ApiModels.QueryRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().scoreThreshold() : null;
    }

    private static Boolean tuningRerankEnabled(ApiModels.QueryRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().rerankEnabled() : null;
    }

    private static Integer tuningTopK(ApiModels.RagAnswerRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().topK() : null;
    }

    private static Integer tuningCandidateK(ApiModels.RagAnswerRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().candidateK() : null;
    }

    private static Double tuningScoreThreshold(ApiModels.RagAnswerRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().scoreThreshold() : null;
    }

    private static Boolean tuningRerankEnabled(ApiModels.RagAnswerRequest request) {
        return request.retrievalTuning() != null ? request.retrievalTuning().rerankEnabled() : null;
    }

    private static Boolean queryRewriteEnabled(ApiModels.QueryRequest request) {
        return request.queryRewrite() != null ? request.queryRewrite().enabled() : null;
    }

    private static String queryRewritePrompt(ApiModels.QueryRequest request) {
        return request.queryRewrite() != null ? request.queryRewrite().prompt() : null;
    }

    private static Boolean queryRewriteEnabled(ApiModels.RagAnswerRequest request) {
        return request.queryRewrite() != null ? request.queryRewrite().enabled() : null;
    }

    private static String queryRewritePrompt(ApiModels.RagAnswerRequest request) {
        return request.queryRewrite() != null ? request.queryRewrite().prompt() : null;
    }

    /** 解析最终使用的 LLM 模型，优先按 modelName 匹配，兼容历史配置继续按 modelId 命中。 */
    private ModelMeta resolveLlmModel(String modelSelector) {
        List<ModelMeta> llmModels = metadataQueryService.listModels(new ModelQueryCondition(ModelType.LLM, null));
        String requestedModelSelector = normalize(modelSelector);
        String configuredModelSelector = firstNonBlank(
                appProperties.getRag().getAnswer().getDefaultLlmModel(),
                appProperties.getRag().getAnswer().getDefaultLlmModelId()
        );
        String effectiveModelSelector = requestedModelSelector != null
                ? requestedModelSelector
                : configuredModelSelector;
        if (StringUtils.isBlank(effectiveModelSelector)) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_CONFIGURATION,
                    "问答模型未配置，请检查 app.rag.answer.default-llm-model 或 app.rag.answer.default-llm-model-id"
            );
        }
        String normalizedModelSelector = normalizeForComparison(effectiveModelSelector);
        return llmModels.stream()
                .filter(model -> matchesModel(model, effectiveModelSelector, normalizedModelSelector))
                .findFirst()
                .orElseThrow(() -> new RagServiceException(
                        RagErrorCode.INVALID_CONFIGURATION,
                        "问答模型不存在或未启用: " + effectiveModelSelector
                ));
    }

    private ModelEndpointSpec toEndpoint(ModelMeta modelMeta) {
        return new ModelEndpointSpec(modelMeta.baseUrl(), modelMeta.apiKey(), modelMeta.modelName());
    }

    /**
     * 组装发送给 LLM 的消息列表。
     *
     * <p>系统提示词来自执行计划或默认配置，用户消息中会拼接检索回来的知识片段。
     */
    private List<LlmMessage> buildAnswerMessages(
            ExecutionPlan executionPlan,
            ApiModels.RagAnswerRequest request,
            List<RetrievedChunk> chunks,
            Map<String, DocumentMeta> documentMetas,
            List<ReferenceSource> referenceSources,
            TerminologyPromptContext terminologyContext
    ) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage(LlmMessageRole.SYSTEM, resolveSystemPrompt(executionPlan.systemPrompt())));

        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于下面检索到的知识片段回答用户问题。");
        prompt.append('\n').append("如果知识片段不足以支持结论，请明确说明。");
        prompt.append('\n').append("不要在正文中输出来源编号、参考资料章节、Markdown 链接或 URL；参考资料由系统自动追加。");
        prompt.append('\n').append('\n').append("用户问题：").append(request.query());
        appendTerminologyContext(prompt, terminologyContext);
        prompt.append('\n').append('\n').append("知识片段：");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            DocumentMeta documentMeta = documentMetas.get(chunk.documentId());
            String docName = documentMeta == null ? null : documentMeta.name();
            ReferenceSource referenceSource = findReferenceSource(referenceSources, chunk, documentMeta);
            prompt.append('\n')
                    .append("[片段 ").append(i + 1).append(']');
            if (referenceSource != null) {
                prompt.append(" 来源文件：").append(referenceSource.fileName());
            } else if (docName != null) {
                prompt.append(" 来源：").append(docName);
            }
            prompt.append('\n')
                    .append(chunk.content());
        }
        messages.add(new LlmMessage(LlmMessageRole.USER, prompt.toString()));
        return List.copyOf(messages);
    }

    private static void appendTerminologyContext(
            StringBuilder prompt,
            TerminologyPromptContext terminologyContext
    ) {
        if (terminologyContext == null || !terminologyContext.changed()) {
            return;
        }
        prompt.append('\n').append('\n').append("术语标准化信息：");
        prompt.append('\n').append("请结合以下映射理解用户原意，并在回答中优先使用专业术语。");
        for (TerminologyReplacement replacement : terminologyContext.applied()) {
            prompt.append('\n').append("- ")
                    .append(replacement.colloquial())
                    .append(" → ")
                    .append(replacement.term());
            if (replacement.knowledgeBaseId() != null) {
                prompt.append("（知识库 ").append(replacement.knowledgeBaseId()).append("）");
            }
        }
        prompt.append('\n').append("实际检索问题变体：");
        for (String normalizedQuery : terminologyContext.normalizedQueries()) {
            prompt.append('\n').append("- ").append(normalizedQuery);
        }
    }

    private String resolveSystemPrompt(String requestSystemPrompt) {
        String effectivePrompt = normalize(requestSystemPrompt);
        if (effectivePrompt != null) {
            return effectivePrompt;
        }
        effectivePrompt = appProperties.getRag().getAnswer().getDefaultSystemPrompt();
        if (effectivePrompt == null) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_CONFIGURATION,
                    "问答系统提示词未配置，请检查 app.rag.answer.default-system-prompt"
            );
        }
        return effectivePrompt;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        String normalizedPrimary = normalize(primary);
        return normalizedPrimary != null ? normalizedPrimary : normalize(fallback);
    }

    private boolean matchesModel(ModelMeta model, String configuredValue, String normalizedConfiguredValue) {
        if (configuredValue.equals(model.modelName())) {
            return true;
        }
        if (configuredValue.equals(model.modelId())) {
            return true;
        }
        String normalizedModelName = normalizeForComparison(model.modelName());
        return normalizedConfiguredValue != null && normalizedConfiguredValue.equals(normalizedModelName);
    }

    private String normalizeForComparison(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private ApiModels.RetrievalResponse toRetrievalResponse(RetrievalResult result) {
        return new ApiModels.RetrievalResponse(
                result.requestId(),
                result.chunks().stream()
                        .map(chunk -> new ApiModels.RetrievedChunkView(
                                chunk.chunkId(),
                                chunk.documentId(),
                                chunk.knowledgeBaseId(),
                                chunk.vectorScore(),
                                chunk.sparseScore(),
                                chunk.rankingScore(),
                                chunk.content(),
                                chunk.metadata()
                        ))
                        .toList(),
                result.debugTrace()
        );
    }

    /** 预先完成检索和模型解析，生成后续问答阶段需要的上下文。 */
    private PreparedAnswer prepareAnswer(
            ApiModels.RagAnswerRequest request,
            RagProcessEventPublisher eventPublisher
    ) {
        ExecutionPlan executionPlan = planAnswer(request);
        RetrievalPlan retrievalPlan = executionPlan.primaryRetrievalPlan();
        ApiModels.MemoryConfig memory = request.memory();
        ConversationMemoryContext memoryContext = conversationMemoryService.resolve(new ConversationMemoryRequest(
                request.userId(),
                memory != null,
                memory != null ? memory.conversationId() : null
        ));
        RetrievalExecutionOutcome retrievalOutcome = executeRetrievalWithRewrite(
                request.query(),
                queryRewriteEnabled(request),
                queryRewritePrompt(request),
                retrievalPlan,
                eventPublisher,
                memoryContext.conversationId()
        );
        RetrievalResult retrieval = retrievalOutcome.result();
        Map<String, DocumentMeta> documentMetas = resolveDocumentMetas(retrieval.chunks());
        List<ReferenceSource> referenceSources = buildReferenceSources(
                retrieval.chunks(),
                documentMetas,
                resolveDatasetNames()
        );
        ModelMeta llmModel = resolveLlmModel(null);
        LlmRequest llmRequest = new LlmRequest(
                toEndpoint(llmModel),
                buildAnswerMessages(
                        executionPlan,
                        request,
                        retrieval.chunks(),
                        documentMetas,
                        referenceSources,
                        retrievalOutcome.terminologyContext()
                ),
                appProperties.getRag().getAnswer().getDefaultTemperature(),
                appProperties.getRag().getAnswer().getDefaultMaxTokens(),
                memoryContext.userId(),
                memoryContext.conversationId()
        );
        return new PreparedAnswer(executionPlan, retrieval, llmModel, llmRequest, referenceSources);
    }

    private ExecutionPlan planAnswer(ApiModels.RagAnswerRequest request) {
        long start = System.nanoTime();
        recordTrace("planning", "planning.request", Map.of(
                "endpointType", "answer",
                "planType", Objects.toString(request.planType(), "default"),
                "docFilterCount", safeList(request.docIds()).size(),
                "directRetrievalContext", shouldUseRetrievalContext(request)
        ));
        try {
            ExecutionPlan plan = shouldUseRetrievalContext(request)
                    ? preserveRequestPrompt(queryPlannerFacade.plan(toRetrievalContext(request)), request.systemPrompt())
                    : queryPlannerFacade.plan(toQueryPlanRequest(request));
recordTrace("planning", "planning.completed", planningPayload(plan, durationMs(start)));
            return plan;
        } catch (IllegalArgumentException ex) {
            recordTrace("planning", "planning.failed", Map.of("error", summarize(ex), "totalMs", durationMs(start)));
            throw ex;
        } catch (UnsupportedOperationException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", RagErrorCode.PLAN_UNSUPPORTED.name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw new RagServiceException(
                    RagErrorCode.PLAN_UNSUPPORTED,
                    "当前请求使用的规划类型暂不支持: " + Objects.toString(request.planType(), "default"),
                    ex
            );
        } catch (RagServiceException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", ex.errorCode().name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw ex;
        } catch (RuntimeException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", RagErrorCode.PLANNING_FAILED.name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw new RagServiceException(
                    RagErrorCode.PLANNING_FAILED,
                    "查询规划失败: " + summarize(ex),
                    ex
            );
        }
    }

    private ExecutionPlan planSearch(ApiModels.QueryRequest request) {
        long start = System.nanoTime();
        recordTrace("planning", "planning.request", Map.of(
                "endpointType", "search",
                "planType", Objects.toString(request.planType(), "default"),
                "docFilterCount", safeList(request.docIds()).size(),
                "directRetrievalContext", shouldUseRetrievalContext(request)
        ));
        try {
            ExecutionPlan plan = shouldUseRetrievalContext(request)
                    ? preserveRequestPrompt(queryPlannerFacade.plan(toRetrievalContext(request)), request.systemPrompt())
                    : queryPlannerFacade.plan(toQueryPlanRequest(request));
            if (log.isInfoEnabled()) {
                String effectiveModes = plan.retrievalPlans().stream()
                        .flatMap(retrievalPlan -> retrievalPlan.recallSpecs().stream())
                        .map(spec -> spec.knowledgeBaseId() + ":" + spec.retrievalMode())
                        .collect(java.util.stream.Collectors.joining(", "));
                log.info("检索计划生效模式 | requested={}, effective=[{}]",
                        request.retrievalMode() == null ? "(default)" : request.retrievalMode(),
                        effectiveModes);
            }
            recordTrace("planning", "planning.completed", planningPayload(plan, durationMs(start)));
            return plan;
        } catch (IllegalArgumentException ex) {
            recordTrace("planning", "planning.failed", Map.of("error", summarize(ex), "totalMs", durationMs(start)));
            throw ex;
        } catch (UnsupportedOperationException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", RagErrorCode.PLAN_UNSUPPORTED.name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw new RagServiceException(
                    RagErrorCode.PLAN_UNSUPPORTED,
                    "当前检索请求使用的规划类型暂不支持: " + Objects.toString(request.planType(), "default"),
                    ex
            );
        } catch (RagServiceException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", ex.errorCode().name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw ex;
        } catch (RuntimeException ex) {
            recordTrace("planning", "planning.failed", Map.of(
                    "errorCode", RagErrorCode.PLANNING_FAILED.name(),
                    "error", summarize(ex),
                    "totalMs", durationMs(start)
            ));
            throw new RagServiceException(
                    RagErrorCode.PLANNING_FAILED,
                    "检索规划失败: " + summarize(ex),
                    ex
            );
        }
    }

    private RetrievalResult executeRetrieval(
            RetrievalPlan retrievalPlan,
            RagProcessEventPublisher eventPublisher,
            int queryIndex,
            int queryCount
    ) {
        long traceStart = System.nanoTime();
        List<Map<String, Object>> knowledgeBases = eventPublisher == RagProcessEventPublisher.NO_OP
                ? List.of()
                : resolveKnowledgeBaseSummaries(retrievalPlan);
        recordTrace("retrieval", "retrieval.started", retrievalEventDetails(
                retrievalPlan, knowledgeBases, queryIndex, queryCount, null));
        StageHandle handle = eventPublisher.start(
                RagProcessStage.RETRIEVAL,
                retrievalEventDetails(retrievalPlan, knowledgeBases, queryIndex, queryCount, null)
        );
        try {
            RetrievalResult result = eventPublisher == RagProcessEventPublisher.NO_OP
                    ? retrievalEngine.execute(retrievalPlan)
                    : retrievalEngine.execute(retrievalPlan, eventPublisher);
            eventPublisher.complete(
                    handle,
                    retrievalEventDetails(retrievalPlan, knowledgeBases, queryIndex, queryCount, result.chunks().size())
            );
            Map<String, Object> payload = new LinkedHashMap<>(retrievalEventDetails(
                    retrievalPlan, knowledgeBases, queryIndex, queryCount, result.chunks().size()));
            payload.put("requestId", result.requestId());
            payload.put("elapsedMs", durationMs(traceStart));
            recordTrace("retrieval", "retrieval.completed", payload);
            return result;
        } catch (RagServiceException ex) {
            eventPublisher.fail(handle, ex.errorCode().name());
            recordTrace("retrieval", "retrieval.failed", Map.of(
                    "errorCode", ex.errorCode().name(),
                    "error", summarize(ex),
                    "queryIndex", queryIndex,
                    "queryCount", queryCount,
                    "elapsedMs", durationMs(traceStart)
            ));
            throw ex;
        } catch (RuntimeException ex) {
            eventPublisher.fail(handle, RagErrorCode.RETRIEVAL_FAILED.name());
            recordTrace("retrieval", "retrieval.failed", Map.of(
                    "errorCode", RagErrorCode.RETRIEVAL_FAILED.name(),
                    "error", summarize(ex),
                    "queryIndex", queryIndex,
                    "queryCount", queryCount,
                    "elapsedMs", durationMs(traceStart)
            ));
            throw new RagServiceException(
                    RagErrorCode.RETRIEVAL_FAILED,
                    "知识检索失败: " + summarize(ex),
                    ex
            );
        }
    }

    private List<Map<String, Object>> resolveKnowledgeBaseSummaries(RetrievalPlan retrievalPlan) {
        Map<String, KnowledgeBaseMeta> knowledgeBasesById = metadataQueryService
                .listKnowledgeBases(KnowledgeBaseQueryCondition.all())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeBaseMeta::knowledgeBaseId,
                        knowledgeBase -> knowledgeBase
                ));
        return retrievalPlan.recallSpecs().stream()
                .map(spec -> {
                    KnowledgeBaseMeta knowledgeBase = knowledgeBasesById.get(spec.knowledgeBaseId());
                    String name = knowledgeBase != null ? knowledgeBase.name() : spec.knowledgeBaseId();
                    return Map.<String, Object>of(
                            "knowledgeBaseId", spec.knowledgeBaseId(),
                            "name", name
                    );
                })
                .toList();
    }

    private static Map<String, Object> retrievalEventDetails(
            RetrievalPlan retrievalPlan,
            List<Map<String, Object>> knowledgeBases,
            int queryIndex,
            int queryCount,
            Integer resultCount
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("knowledgeBaseCount", retrievalPlan.recallSpecs().size());
        details.put("knowledgeBases", knowledgeBases);
        details.put("queryIndex", queryIndex);
        details.put("queryCount", queryCount);
        if (resultCount != null) {
            details.put("resultCount", resultCount);
        }
        return Map.copyOf(details);
    }

    private RetrievalExecutionOutcome executeRetrievalWithRewrite(
            String originalQuery,
            Boolean queryRewrite,
            String queryRewritePrompt,
            RetrievalPlan basePlan,
            RagProcessEventPublisher eventPublisher,
            String conversationId
    ) {
        long summaryStart = System.nanoTime();
        List<Map<String, Object>> knowledgeBases = resolveKnowledgeBaseSummaries(basePlan);
        if (null == queryRewrite || Boolean.FALSE.equals(queryRewrite)) {
            recordTrace("query_rewrite", "query_rewrite.summary", Map.of(
                    "originalQuery", originalQuery,
                    "enabled", false,
                    "rewrittenCount", 0,
                    "totalQueryCount", 1,
                    "rewrittenQueries", List.of(),
                    "elapsedMs", 0
            ));
            // 替换作用于计划自身的 query（实际送入 embedding 的文本）：
            // 未发生替换时分组文本与 basePlan.query() 恒等 → 复用原计划实例，与无术语特性时的路径完全一致
            TerminologyExpansion expansion = expandTerminology(basePlan.query(), basePlan, eventPublisher);
            RetrievalResult result = executeTerminologyExpansion(basePlan, expansion, eventPublisher);
            recordTrace("retrieval", "retrieval.summary", retrievalSummaryPayload(
                    originalQuery,
                    List.of(),
                    expansion.executedQueries(),
                    originalQuery,
                    false,
                    originalQuery,
                    knowledgeBases,
                    List.of(result),
                    result,
                    false,
                    0,
                    rankingMode(result, "single_query"),
                    durationMs(summaryStart)
            ));
            return new RetrievalExecutionOutcome(
                    withTerminologyDebug(result, TerminologyPromptContext.from(List.of(expansion))),
                    TerminologyPromptContext.from(List.of(expansion))
            );
        }

        QueryRewriteResult rewriteResult = rewriteQuery(originalQuery, queryRewritePrompt, eventPublisher, conversationId);
        List<String> subQueries = rewriteResult.retrievalQueries();
        List<String> allQueries = rewriteResult.executedQueries(originalQuery);
        String rerankQuery = rewriteResult.rerankQuery(originalQuery);
        log.info("Query 改写完成，dependsOnHistory={}，执行query数={}，rerankQuery={}",
                rewriteResult.dependsOnHistory(), allQueries.size(), preview(rerankQuery));

        TerminologyRetrievalBatch batch = executeRetrievalQueriesInParallel(basePlan, allQueries, eventPublisher);
        List<RetrievalResult> results = batch.results();

        List<RetrievedChunk> merged = mergeChunks(results);
        recordTrace("retrieval", "retrieval.rewrite_merge.started", Map.of(
                "queryCount", batch.executedQueries().size(),
                "inputResultCount", results.stream().mapToInt(result -> result.chunks().size()).sum(),
                "dedupedCount", merged.size()
        ));

        ModelEndpointSpec rerankModel = extractRerankModel(basePlan);
        boolean rerankApplied = rerankModel != null && !merged.isEmpty();
        long finalRerankMs = 0;
        if (rerankModel != null && !merged.isEmpty()) {
            long rerankStart = System.nanoTime();
            merged = withKnowledgeBaseNames(merged, knowledgeBases);
            merged = eventPublisher == RagProcessEventPublisher.NO_OP
                    ? retrievalEngine.rerank(rerankQuery, merged, rerankModel, basePlan.topK())
                    : retrievalEngine.rerank(
                            rerankQuery,
                            merged,
                            rerankModel,
                            basePlan.topK(),
                            eventPublisher,
                            "rewrite_merge"
                    );
            finalRerankMs = durationMs(rerankStart);
        } else if (!merged.isEmpty()) {
            merged = merged.stream()
                    .sorted((a, b) -> Double.compare(b.rankingScore(), a.rankingScore()))
                    .limit(basePlan.topK())
                    .toList();
        }

        recordTrace("retrieval", "retrieval.rewrite_merge.completed", Map.of(
                "queryCount", batch.executedQueries().size(),
                "mergedCount", merged.size(),
                "finalCount", merged.size(),
                "rerankApplied", rerankApplied,
                "dependsOnHistory", rewriteResult.dependsOnHistory(),
                "rerankQuery", rerankQuery
        ));
        RetrievalResult finalResult = new RetrievalResult(UUID.randomUUID().toString(), merged, Map.of(
                "ranking_mode", rerankApplied ? "rewrite_merge_rerank" : "rewrite_merge_score_sort",
                "final_rerank_ms", finalRerankMs
        ));
        finalResult = withTerminologyDebug(finalResult, batch.terminologyContext());
        recordTrace("retrieval", "retrieval.summary", retrievalSummaryPayload(
                originalQuery,
                subQueries,
                batch.executedQueries(),
                rerankQuery,
                rewriteResult.dependsOnHistory(),
                rewriteResult.resolvedQuestion(),
                knowledgeBases,
                results,
                finalResult,
                rerankApplied,
                finalRerankMs,
                rerankApplied ? "rewrite_merge_rerank" : "rewrite_merge_score_sort",
                durationMs(summaryStart)
        ));
        return new RetrievalExecutionOutcome(finalResult, batch.terminologyContext());
    }

    private TerminologyRetrievalBatch executeRetrievalQueriesInParallel(
            RetrievalPlan basePlan,
            List<String> allQueries,
            RagProcessEventPublisher eventPublisher
    ) {
        record VariantTask(TerminologyQueryGroup group) {
        }
        List<VariantTask> variantTasks = new ArrayList<>(allQueries.size());
        List<TerminologyExpansion> expansions = new ArrayList<>(allQueries.size());
        for (int i = 0; i < allQueries.size(); i++) {
            TerminologyExpansion expansion = expandTerminology(allQueries.get(i), basePlan, eventPublisher);
            expansions.add(expansion);
            for (TerminologyQueryGroup group : expansion.groups()) {
                variantTasks.add(new VariantTask(group));
            }
        }

        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        List<Callable<RetrievalResult>> tasks = new ArrayList<>(variantTasks.size());
        for (int i = 0; i < variantTasks.size(); i++) {
            VariantTask variantTask = variantTasks.get(i);
            int taskIndex = i + 1;
            tasks.add(() -> {
                restoreMdc(capturedMdc);
                try {
                    return executeRetrieval(
                            withQueryAndSpecs(basePlan, variantTask.group().queryText(), variantTask.group().recallSpecs()),
                            eventPublisher,
                            taskIndex,
                            variantTasks.size()
                    );
                } finally {
                    MDC.clear();
                }
            });
        }
        return new TerminologyRetrievalBatch(
                invokeRetrievalTasks(tasks, "改写查询并行检索"),
                variantTasks.stream().map(task -> task.group().queryText()).toList(),
                TerminologyPromptContext.from(expansions)
        );
    }

    private List<RetrievalResult> invokeRetrievalTasks(List<Callable<RetrievalResult>> tasks, String failureContext) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RetrievalResult>> futures = executor.invokeAll(tasks);
            List<RetrievalResult> results = new ArrayList<>(futures.size());
            for (Future<RetrievalResult> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RagServiceException(
                    RagErrorCode.RETRIEVAL_FAILED,
                    failureContext + "被中断，请稍后重试",
                    ex
            );
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RagServiceException ragServiceException) {
                throw ragServiceException;
            }
            throw new RagServiceException(
                    RagErrorCode.RETRIEVAL_FAILED,
                    failureContext + "失败: " + summarize(cause),
                    cause
            );
        }
    }

    private static void restoreMdc(Map<String, String> capturedMdc) {
        if (capturedMdc == null || capturedMdc.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(capturedMdc);
    }

    private static List<RetrievedChunk> withKnowledgeBaseNames(
            List<RetrievedChunk> chunks,
            List<Map<String, Object>> knowledgeBases
    ) {
        Map<String, String> namesById = new LinkedHashMap<>();
        for (Map<String, Object> knowledgeBase : knowledgeBases) {
            Object knowledgeBaseId = knowledgeBase.get("knowledgeBaseId");
            Object name = knowledgeBase.get("name");
            if (knowledgeBaseId != null && name != null) {
                namesById.put(knowledgeBaseId.toString(), name.toString());
            }
        }
        if (namesById.isEmpty()) {
            return chunks;
        }
        return chunks.stream()
                .map(chunk -> withKnowledgeBaseName(chunk, namesById.get(chunk.knowledgeBaseId())))
                .toList();
    }

    private static RetrievedChunk withKnowledgeBaseName(RetrievedChunk chunk, String knowledgeBaseName) {
        if (knowledgeBaseName == null || knowledgeBaseName.isBlank()) {
            return chunk;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
        metadata.put("knowledgeBaseName", knowledgeBaseName);
        return new RetrievedChunk(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.knowledgeBaseId(),
                chunk.vectorScore(),
                chunk.sparseScore(),
                chunk.rankingScore(),
                chunk.content(),
                metadata
        );
    }

    private Map<String, Object> retrievalSummaryPayload(
            String originalQuery,
            List<String> rewrittenQueries,
            List<String> executedQueries,
            String rerankQuery,
            boolean dependsOnHistory,
            String resolvedQuestion,
            List<Map<String, Object>> knowledgeBases,
            List<RetrievalResult> queryResults,
            RetrievalResult finalResult,
            boolean finalRerankApplied,
            long finalRerankMs,
            String finalRankingMode,
            long elapsedMs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", finalResult.requestId());
        payload.put("originalQuery", originalQuery);
        payload.put("rewrittenCount", rewrittenQueries.size());
        payload.put("rewrittenQueries", rewrittenQueries);
        payload.put("executedQueryCount", executedQueries.size());
        payload.put("executedQueries", executedQueries);
        payload.put("dependsOnHistory", dependsOnHistory);
        payload.put("resolvedQuestion", Objects.toString(resolvedQuestion, ""));
        payload.put("rerankQuery", Objects.toString(rerankQuery, ""));
        payload.put("knowledgeBaseCount", knowledgeBases.size());
        payload.put("knowledgeBases", knowledgeBases);
        payload.put("perQueryResults", perQueryResults(executedQueries, queryResults));
        payload.put("candidateCount", candidateCount(queryResults, finalResult));
        payload.put("finalChunkCount", finalResult.chunks().size());
        payload.put("finalChunks", chunkSummaries(finalResult.chunks()));
        payload.put("finalRerankApplied", finalRerankApplied);
        payload.put("finalRerankMs", finalRerankMs);
        payload.put("finalRankingMode", finalRankingMode);
        payload.put("elapsedMs", elapsedMs);
        return Map.copyOf(payload);
    }

    private static List<Map<String, Object>> perQueryResults(
            List<String> executedQueries,
            List<RetrievalResult> queryResults
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < queryResults.size(); i++) {
            RetrievalResult result = queryResults.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queryIndex", i + 1);
            if (i < executedQueries.size()) {
                row.put("query", executedQueries.get(i));
            }
            row.put("requestId", result.requestId());
            row.put("resultCount", result.chunks().size());
            row.put("rankingMode", rankingMode(result, ""));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static int candidateCount(List<RetrievalResult> queryResults, RetrievalResult finalResult) {
        int total = queryResults.stream()
                .map(RetrievalResult::debugTrace)
                .mapToInt(trace -> numberValue(trace.get("candidate_count")))
                .sum();
        return total > 0 ? total : finalResult.chunks().size();
    }

    private static String rankingMode(RetrievalResult result, String fallback) {
        Object rankingMode = result.debugTrace().get("ranking_mode");
        return rankingMode == null ? fallback : Objects.toString(rankingMode, fallback);
    }

    private static int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static List<Map<String, Object>> chunkSummaries(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("chunkId", chunk.chunkId());
            row.put("documentId", chunk.documentId());
            row.put("knowledgeBaseId", chunk.knowledgeBaseId());
            row.put("vectorScore", chunk.vectorScore());
            row.put("sparseScore", chunk.sparseScore());
            row.put("rankingScore", chunk.rankingScore());
            row.put("content", chunk.content());
            row.put("metadata", chunk.metadata());
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private QueryRewriteResult rewriteQuery(
            String originalQuery,
            String queryRewritePrompt,
            RagProcessEventPublisher eventPublisher,
            String conversationId
    ) {
        long start = System.nanoTime();
        StageHandle handle = eventPublisher.start(RagProcessStage.QUERY_REWRITE, Map.of());

        try {
            String effectivePrompt = firstNonBlank(
                    queryRewritePrompt,
                    appProperties.getRag().getAnswer().getQueryRewrite().getDefaultPrompt()
            );
            ModelMeta llmModel = resolveLlmModel(null);
            List<String> historyUserMessages = queryRewriteHistory(conversationId);
            String rewriteUserPrompt = buildRewriteUserPrompt(originalQuery, historyUserMessages);
            recordTrace("query_rewrite", "query_rewrite.started", Map.of(
                    "originalQueryLength", originalQuery.length(),
                    "originalQueryPreview", preview(originalQuery),
                    "promptLength", effectivePrompt.length(),
                    "modelId", llmModel.modelId(),
                    "historyUserMessageCount", historyUserMessages.size()
            ));
            LlmRequest rewriteRequest = new LlmRequest(
                    toEndpoint(llmModel),
                    List.of(
                            new LlmMessage(LlmMessageRole.SYSTEM, effectivePrompt),
                            new LlmMessage(LlmMessageRole.USER, rewriteUserPrompt)
                    ),
                    appProperties.getRag().getAnswer().getQueryRewrite().getTemperature(),
                    256,
                    null,
                    null
            );
            recordTrace("query_rewrite", "query_rewrite.llm.request", Map.of(
                    "modelId", llmModel.modelId(),
                    "temperature", appProperties.getRag().getAnswer().getQueryRewrite().getTemperature(),
                    "maxTokens", 256,
                    "historyUserMessageCount", historyUserMessages.size()
            ));
            LlmResponse response = llmService.generate(rewriteRequest);
            recordTrace("query_rewrite", "query_rewrite.llm.response", Map.of(
                    "responseLength", response.content() == null ? 0 : response.content().length(),
                    "responsePreview", preview(response.content()),
                    "finishReason", Objects.toString(response.finishReason(), "")
            ));
            int maxQueries = appProperties.getRag().getAnswer().getQueryRewrite().getMaxRewriteQueries();
            QueryRewriteResult rewriteResult = parseQueryRewriteResult(response.content(), maxQueries, originalQuery);
            log.info("Query 改写生成子query数={}，dependsOnHistory={}",
                    rewriteResult.retrievalQueries().size(), rewriteResult.dependsOnHistory());
            eventPublisher.complete(handle, Map.of("queryCount", rewriteResult.executedQueryCount(originalQuery)));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("originalQuery", originalQuery);
            summary.put("rewrittenCount", rewriteResult.retrievalQueries().size());
            summary.put("totalQueryCount", rewriteResult.executedQueryCount(originalQuery));
            summary.put("rewrittenQueries", rewriteResult.retrievalQueries());
            summary.put("dependsOnHistory", rewriteResult.dependsOnHistory());
            summary.put("resolvedQuestion", rewriteResult.resolvedQuestion());
            summary.put("modelId", llmModel.modelId());
            summary.put("historyUserMessageCount", historyUserMessages.size());
            summary.put("elapsedMs", durationMs(start));
            recordTrace("query_rewrite", "query_rewrite.summary", summary);
            return rewriteResult;
        } catch (Exception ex) {
            log.warn("Query 改写失败，降级为原始query检索: {}", summarize(ex));
            eventPublisher.fallback(handle, Map.of("queryCount", 1));
            recordTrace("query_rewrite", "query_rewrite.summary", Map.of(
                    "originalQuery", originalQuery,
                    "rewrittenCount", 0,
                    "totalQueryCount", 1,
                    "rewrittenQueries", List.of(),
                    "fallback", true,
                    "reason", summarize(ex),
                    "elapsedMs", durationMs(start)
            ));
            return QueryRewriteResult.fallback();
        }
    }

    private List<String> queryRewriteHistory(String conversationId) {
        AppProperties.QueryRewrite queryRewrite = appProperties.getRag().getAnswer().getQueryRewrite();
        if (!queryRewrite.isHistoryEnabled()
                || conversationId == null
                || conversationId.isBlank()
                || queryRewrite.getHistoryUserMessageLimit() <= 0) {
            return List.of();
        }
        return conversationMemoryService.recentUserMessages(
                conversationId,
                queryRewrite.getHistoryUserMessageLimit()
        );
    }

    private static String buildRewriteUserPrompt(String originalQuery, List<String> historyUserMessages) {
        if (historyUserMessages == null || historyUserMessages.isEmpty()) {
            return originalQuery;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前问题：").append(originalQuery);
        prompt.append('\n').append('\n').append("最近历史问题：");
        for (int i = 0; i < historyUserMessages.size(); i++) {
            prompt.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(historyUserMessages.get(i));
        }
        prompt.append('\n').append('\n')
                .append("请判断当前问题是否依赖最近历史问题。")
                .append("如果当前问题与历史问题毫无关系，请完全忽略历史问题，只基于当前问题改写；")
                .append("如果当前问题是追问或省略了历史中的主题，请结合相关历史问题补全检索意图。");
        return prompt.toString();
    }

    private QueryRewriteResult parseQueryRewriteResult(String content, int maxQueries, String originalQuery) {
        if (content == null || content.isBlank()) {
            return QueryRewriteResult.fallback();
        }
        QueryRewriteResult jsonResult = parseJsonQueryRewriteResult(content, maxQueries, originalQuery);
        if (!jsonResult.retrievalQueries().isEmpty() || jsonResult.dependsOnHistory()) {
            return jsonResult;
        }
        List<String> queries = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("{") && !line.startsWith("}") && !line.startsWith("\""))
                .filter(line -> !line.matches("^\\d+[.、)\\s].*"))
                .limit(maxQueries)
                .toList();
        return new QueryRewriteResult(false, originalQuery, queries);
    }

    private QueryRewriteResult parseJsonQueryRewriteResult(String content, int maxQueries, String originalQuery) {
        String json = extractJsonObject(content);
        if (json == null) {
            return QueryRewriteResult.fallback();
        }
        try {
            JsonNode root = QUERY_REWRITE_OBJECT_MAPPER.readTree(json);
            boolean dependsOnHistory = root.path("dependsOnHistory").asBoolean(false);
            String resolvedQuestion = normalize(root.path("resolvedQuestion").asText(""));
            if (resolvedQuestion == null) {
                resolvedQuestion = originalQuery;
            }
            JsonNode queries = root.get("retrievalQueries");
            List<String> rewrittenQueries = new ArrayList<>();
            if (queries != null && queries.isArray()) {
                for (JsonNode query : queries) {
                    if (rewrittenQueries.size() >= maxQueries) {
                        break;
                    }
                    if (query != null && query.isTextual()) {
                        String text = query.asText().trim();
                        if (!text.isBlank()) {
                            rewrittenQueries.add(text);
                        }
                    }
                }
            }
            if (dependsOnHistory && rewrittenQueries.isEmpty()) {
                rewrittenQueries.add(resolvedQuestion);
            }
            return new QueryRewriteResult(dependsOnHistory, resolvedQuestion, rewrittenQueries);
        } catch (RuntimeException ex) {
            log.warn("Query 改写 JSON 解析失败，将使用按行解析兜底: {}", summarize(ex));
            return QueryRewriteResult.fallback();
        } catch (Exception ex) {
            log.warn("Query 改写 JSON 解析失败，将使用按行解析兜底: {}", summarize(ex));
            return QueryRewriteResult.fallback();
        }
    }

    private static String extractJsonObject(String content) {
        String normalized = content == null ? "" : content.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return normalized.substring(start, end + 1);
    }

    private RetrievalPlan withQuery(RetrievalPlan plan, String newQuery) {
        return new RetrievalPlan(
                newQuery,
                plan.embeddingSpec(),
                plan.recallSpecs(),
                plan.globalRankingSpec(),
                plan.topK(),
                plan.globalScoreThresholdEnabled(),
                plan.globalScoreThreshold()
        );
    }

    /**
     * 术语替换后的一个执行分组：同一替换文本 + 该文本对应的知识库召回规格子集。
     */
    private record TerminologyQueryGroup(String queryText, List<KnowledgeBaseRecallSpec> recallSpecs) {
    }

    /**
     * 单个 query 文本的术语展开结果：按替换后文本分组的执行分组 + 命中的替换明细。
     */
    private record TerminologyExpansion(
            String sourceQuery,
            List<TerminologyQueryGroup> groups,
            List<TerminologyReplacement> applied
    ) {
        private static TerminologyExpansion identity(String queryText, List<KnowledgeBaseRecallSpec> recallSpecs) {
            return new TerminologyExpansion(
                    queryText,
                    List.of(new TerminologyQueryGroup(queryText, recallSpecs)),
                    List.of()
            );
        }

        private List<String> executedQueries() {
            return groups.stream().map(TerminologyQueryGroup::queryText).distinct().toList();
        }
    }

    private record TerminologyPromptContext(
            List<String> normalizedQueries,
            List<TerminologyReplacement> applied
    ) {
        private TerminologyPromptContext {
            normalizedQueries = List.copyOf(normalizedQueries);
            applied = List.copyOf(applied);
        }

        private static TerminologyPromptContext from(List<TerminologyExpansion> expansions) {
            List<TerminologyReplacement> replacements = expansions.stream()
                    .flatMap(expansion -> expansion.applied().stream())
                    .distinct()
                    .toList();
            if (replacements.isEmpty()) {
                return new TerminologyPromptContext(List.of(), List.of());
            }
            List<String> normalizedQueries = expansions.stream()
                    .filter(expansion -> !expansion.applied().isEmpty())
                    .flatMap(expansion -> expansion.groups().stream())
                    .map(TerminologyQueryGroup::queryText)
                    .distinct()
                    .toList();
            return new TerminologyPromptContext(normalizedQueries, replacements);
        }

        private boolean changed() {
            return !applied.isEmpty();
        }
    }

    private record TerminologyRetrievalBatch(
            List<RetrievalResult> results,
            List<String> executedQueries,
            TerminologyPromptContext terminologyContext
    ) {
    }

    private record RetrievalExecutionOutcome(
            RetrievalResult result,
            TerminologyPromptContext terminologyContext
    ) {
    }

    /**
     * 把 query 文本按术语规则展开为执行分组：先全局替换，再对每个知识库在全局结果之上叠加 KB 规则，
     * 按「替换后文本」对 recallSpecs 分组。特性关闭或未命中任何规则时返回恒等展开（单组、原文、全量 specs）。
     */
    private TerminologyExpansion expandTerminology(
            String queryText,
            RetrievalPlan plan,
            RagProcessEventPublisher eventPublisher
    ) {
        if (!terminologyService.isEnabled()) {
            return TerminologyExpansion.identity(queryText, plan.recallSpecs());
        }
        TerminologyResult globalResult = terminologyService.replaceGlobal(queryText);
        Map<String, List<KnowledgeBaseRecallSpec>> specsByText = new LinkedHashMap<>();
        List<TerminologyReplacement> applied = new ArrayList<>(globalResult.applied());
        for (KnowledgeBaseRecallSpec spec : plan.recallSpecs()) {
            TerminologyResult kbResult = terminologyService.replaceForKnowledgeBase(globalResult.text(), spec.knowledgeBaseId());
            applied.addAll(kbResult.applied());
            specsByText.computeIfAbsent(kbResult.text(), ignored -> new ArrayList<>()).add(spec);
        }
        if (applied.isEmpty()) {
            return TerminologyExpansion.identity(queryText, plan.recallSpecs());
        }
        List<TerminologyQueryGroup> groups = specsByText.entrySet().stream()
                .map(entry -> new TerminologyQueryGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        TerminologyExpansion expansion = new TerminologyExpansion(queryText, groups, List.copyOf(applied));
        publishTerminologyApplied(expansion, eventPublisher);
        return expansion;
    }

    private RetrievalPlan withQueryAndSpecs(RetrievalPlan plan, String newQuery, List<KnowledgeBaseRecallSpec> specs) {
        return new RetrievalPlan(
                newQuery,
                plan.embeddingSpec(),
                specs,
                plan.globalRankingSpec(),
                plan.topK(),
                plan.globalScoreThresholdEnabled(),
                plan.globalScoreThreshold()
        );
    }

    /**
     * 执行术语展开结果：
     * <ul>
     *   <li>单组且文本未变 → 与原路径完全一致的单次执行（0 次多余 embedding）</li>
     *   <li>单组且文本已变 → 用替换后文本单次执行</li>
     *   <li>多组 → 各分组并行执行后合并去重，按 rankingScore 降序截断 topK</li>
     * </ul>
     */
    private RetrievalResult executeTerminologyExpansion(
            RetrievalPlan basePlan,
            TerminologyExpansion expansion,
            RagProcessEventPublisher eventPublisher
    ) {
        List<TerminologyQueryGroup> groups = expansion.groups();
        if (groups.size() == 1) {
            String queryText = groups.get(0).queryText();
            RetrievalPlan effectivePlan = queryText.equals(basePlan.query())
                    ? basePlan
                    : withQuery(basePlan, queryText);
            return executeRetrieval(effectivePlan, eventPublisher, 1, 1);
        }
        List<RetrievalResult> results = executeTerminologyGroupsInParallel(basePlan, groups, eventPublisher);
        List<RetrievedChunk> merged = mergeChunks(results);
        ModelEndpointSpec rerankModel = extractRerankModel(basePlan);
        boolean finalRerankApplied = rerankModel != null && !merged.isEmpty();
        if (finalRerankApplied) {
            merged = eventPublisher == RagProcessEventPublisher.NO_OP
                    ? retrievalEngine.rerank(basePlan.query(), merged, rerankModel, basePlan.topK())
                    : retrievalEngine.rerank(
                            basePlan.query(),
                            merged,
                            rerankModel,
                            basePlan.topK(),
                            eventPublisher,
                            "terminology_merge"
                    );
        } else {
            merged = merged.stream()
                    .sorted((left, right) -> Double.compare(right.rankingScore(), left.rankingScore()))
                    .limit(basePlan.topK())
                    .toList();
        }
        return new RetrievalResult(UUID.randomUUID().toString(), merged, Map.of(
                "ranking_mode", finalRerankApplied ? "terminology_merge_rerank" : "terminology_merge_score_sort",
                "terminology_applied", expansion.applied(),
                "terminology_queries", expansion.executedQueries()
        ));
    }

    private static RetrievalResult withTerminologyDebug(
            RetrievalResult result,
            TerminologyPromptContext terminologyContext
    ) {
        if (terminologyContext == null || !terminologyContext.changed()) {
            return result;
        }
        Map<String, Object> debugTrace = new LinkedHashMap<>(result.debugTrace());
        debugTrace.put("terminology_applied", terminologyContext.applied());
        debugTrace.put("terminology_queries", terminologyContext.normalizedQueries());
        return new RetrievalResult(result.requestId(), result.chunks(), debugTrace);
    }

    private List<RetrievalResult> executeTerminologyGroupsInParallel(
            RetrievalPlan basePlan,
            List<TerminologyQueryGroup> groups,
            RagProcessEventPublisher eventPublisher
    ) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        List<Callable<RetrievalResult>> tasks = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            TerminologyQueryGroup group = groups.get(i);
            int groupIndex = i + 1;
            tasks.add(() -> {
                restoreMdc(capturedMdc);
                try {
                    return executeRetrieval(
                            withQueryAndSpecs(basePlan, group.queryText(), group.recallSpecs()),
                            eventPublisher,
                            groupIndex,
                            groups.size()
                    );
                } finally {
                    MDC.clear();
                }
            });
        }
        return invokeRetrievalTasks(tasks, "术语分组并行检索");
    }

    /**
     * 记录术语替换命中明细：trace 事件 + SSE 进度事件（仅当确有替换时调用）。
     */
    private void publishTerminologyApplied(TerminologyExpansion expansion, RagProcessEventPublisher eventPublisher) {
        List<Map<String, Object>> appliedReplacements = expansion.applied().stream()
                .map(replacement -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("colloquial", replacement.colloquial());
                    item.put("term", replacement.term());
                    item.put("scope", replacement.scope());
                    if (replacement.knowledgeBaseId() != null) {
                        item.put("knowledgeBaseId", replacement.knowledgeBaseId());
                    }
                    return item;
                })
                .toList();
        List<String> executedQueries = expansion.groups().stream()
                .map(TerminologyQueryGroup::queryText)
                .distinct()
                .toList();
        recordTrace("terminology", "terminology.replacement.applied", Map.of(
                "appliedCount", appliedReplacements.size(),
                "appliedReplacements", appliedReplacements,
                "executedQueries", executedQueries
        ));
        log.info("术语替换已应用，命中数={}，执行文本数={}", appliedReplacements.size(), executedQueries.size());
        if (eventPublisher == RagProcessEventPublisher.NO_OP) {
            return;
        }
        StageHandle handle = eventPublisher.start(
                RagProcessStage.TERMINOLOGY_REPLACEMENT,
                Map.of("appliedCount", appliedReplacements.size())
        );
        eventPublisher.complete(handle, Map.of(
                "appliedCount", appliedReplacements.size(),
                "appliedReplacements", appliedReplacements,
                "executedQueries", executedQueries
        ));
    }

    private List<RetrievedChunk> mergeChunks(List<RetrievalResult> results) {
        Map<String, RetrievedChunk> deduped = new LinkedHashMap<>();
        for (RetrievalResult result : results) {
            for (RetrievedChunk chunk : result.chunks()) {
                deduped.merge(chunk.chunkId(), chunk, (existing, incoming) ->
                        incoming.rankingScore() > existing.rankingScore() ? incoming : existing
                );
            }
        }
        return List.copyOf(deduped.values());
    }

    private ModelEndpointSpec extractRerankModel(RetrievalPlan plan) {
        if (plan.rankingSpec() instanceof RankingSpec.RerankRankingSpec rerankSpec) {
            return rerankSpec.modelEndpoint();
        }
        return null;
    }

    private boolean shouldUseRetrievalContext(ApiModels.QueryRequest request) {
        return tuningTopK(request) != null
                || tuningCandidateK(request) != null
                || tuningScoreThreshold(request) != null
                || tuningRerankEnabled(request) != null;
    }

    private boolean shouldUseRetrievalContext(ApiModels.RagAnswerRequest request) {
        return tuningTopK(request) != null
                || tuningCandidateK(request) != null
                || tuningScoreThreshold(request) != null
                || tuningRerankEnabled(request) != null;
    }

    private ExecutionPlan preserveRequestPrompt(ExecutionPlan executionPlan, String requestSystemPrompt) {
        if (normalize(requestSystemPrompt) == null) {
            return executionPlan;
        }
        return new ExecutionPlan(
                executionPlan.planType(),
                executionPlan.query(),
                requestSystemPrompt,
                executionPlan.retrievalPlans(),
                executionPlan.orchestrationOptions()
        );
    }

    private int positiveOrZero(Integer value) {
        return value == null || value <= 0 ? 0 : value;
    }

    private List<String> resolveKnowledgeBaseIds(List<String> docIds) {
        return metadataQueryService.listKnowledgeBases(
                new KnowledgeBaseQueryCondition(docIds)
        ).stream()
                .map(kb -> kb.knowledgeBaseId())
                .toList();
    }

    private Map<String, DocumentMeta> resolveDocumentMetas(List<RetrievedChunk> chunks) {
        List<String> docIds = chunks.stream()
                .map(RetrievedChunk::documentId)
                .distinct()
                .toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return metadataQueryService.getDocumentMetas(docIds);
    }

    private List<ReferenceSource> buildReferenceSources(
            List<RetrievedChunk> chunks,
            Map<String, DocumentMeta> documentMetas,
            Map<String, String> datasetNames
    ) {
        String difyFilesUrl = appProperties.getRag().getDifyFilesUrl();
        Map<String, ReferenceSource> sources = new LinkedHashMap<>();
        Map<String, List<String>> refsBySource = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> metadata = chunk.metadata();
            DocumentMeta documentMeta = documentMetas.get(chunk.documentId());
            String metadataFileName = metadataValue(metadata, "document_name");
            String uploadFileId = firstNonBlank(
                    metadataValue(metadata, "upload_file_id"),
                    documentMeta == null ? null : documentMeta.uploadFileId()
            );
            String uploadFileKey = documentMeta == null ? null : documentMeta.uploadFileKey();
            String fileName = firstNonBlank(metadataFileName, documentMeta == null ? null : documentMeta.name());
            if (fileName == null) {
                continue;
            }
            String defaultPath = buildFilePath(difyFilesUrl, uploadFileId, uploadFileKey, fileName);
            String path = resolveReferencePath(documentMeta, defaultPath);
            String key = referenceSourceKey(chunk.documentId(), fileName, uploadFileId, path);
            // 分段索引节点 ID（dify metadata JSON 的 doc_id）；缺失时退回检索链路的原始 chunk 标识
            String indexNodeId = metadataValue(metadata, "doc_id");
            String ref = indexNodeId != null && !indexNodeId.isBlank() ? indexNodeId : chunk.chunkId();
            List<String> refs = refsBySource.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
            sources.computeIfAbsent(key, ignored -> new ReferenceSource(
                    sources.size() + 1,
                    key,
                    fileName,
                    path,
                    chunk.documentId(),
                    resolveDatasetName(datasetNames, chunk, documentMeta),
                    List.of()
            ));
        }
        Map<String, String> positions = resolveSegmentPositions(refsBySource.values().stream()
                .flatMap(List::stream)
                .toList());
        return sources.values().stream()
                .map(source -> new ReferenceSource(
                        source.index(),
                        source.key(),
                        source.fileName(),
                        source.path(),
                        source.documentId(),
                        source.datasetName(),
                        chunkIdsForRefs(refsBySource.getOrDefault(source.key(), List.of()), positions)
                ))
                .toList();
    }

    /**
     * 解析知识库（数据集）ID 到名称的映射，用于在引用信息中标注数据集名称。
     */
    private Map<String, String> resolveDatasetNames() {
        try {
            return metadataQueryService.listKnowledgeBases(KnowledgeBaseQueryCondition.all()).stream()
                    .filter(knowledgeBase -> knowledgeBase.name() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            KnowledgeBaseMeta::knowledgeBaseId,
                            KnowledgeBaseMeta::name,
                            (existing, ignored) -> existing,
                            LinkedHashMap::new
                    ));
        } catch (RuntimeException ex) {
            log.warn("解析数据集名称失败，引用信息将不携带 datasetName | error={}", summarize(ex));
            return Map.of();
        }
    }

    private String resolveDatasetName(
            Map<String, String> datasetNames,
            RetrievedChunk chunk,
            DocumentMeta documentMeta
    ) {
        String knowledgeBaseId = firstNonBlank(
                chunk.knowledgeBaseId(),
                documentMeta == null ? null : documentMeta.knowledgeBaseId()
        );
        return datasetNameOrId(datasetNames, knowledgeBaseId);
    }

    /** 数据集名称缺失时退回数据集 ID，保证引用条目始终有可展示的来源标识。 */
    private static String datasetNameOrId(Map<String, String> datasetNames, String knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return null;
        }
        String datasetName = datasetNames.get(knowledgeBaseId);
        return datasetName == null || datasetName.isBlank() ? knowledgeBaseId : datasetName;
    }

    private ReferenceSource findReferenceSource(
            List<ReferenceSource> sources,
            RetrievedChunk chunk,
            DocumentMeta documentMeta
    ) {
        Map<String, Object> metadata = chunk.metadata();
        String fileName = metadataValue(metadata, "document_name");
        String uploadFileId = firstNonBlank(
                metadataValue(metadata, "upload_file_id"),
                documentMeta == null ? null : documentMeta.uploadFileId()
        );
        fileName = firstNonBlank(fileName, documentMeta == null ? null : documentMeta.name());
        if (fileName == null) {
            return null;
        }
        String path = buildFilePath(
                appProperties.getRag().getDifyFilesUrl(),
                uploadFileId,
                documentMeta == null ? null : documentMeta.uploadFileKey(),
                fileName
        );
        path = resolveReferencePath(documentMeta, path);
        String key = referenceSourceKey(chunk.documentId(), fileName, uploadFileId, path);
        return sources.stream()
                .filter(source -> source.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : normalizeStatic(String.valueOf(value));
    }

    private static String referenceSourceKey(String documentId, String fileName, String uploadFileId, String path) {
        if (uploadFileId != null) {
            return "upload:" + uploadFileId + ":" + fileName + ":" + Objects.toString(path, "");
        }
        return "document:" + Objects.toString(documentId, "") + ":" + fileName;
    }

    private LlmResponse generateAnswer(LlmRequest llmRequest, List<ReferenceSource> referenceSources) {
        long start = System.nanoTime();
        recordTrace("answer_generation", "answer_generation.started", Map.of(
                "model", llmRequest.modelEndpoint().model(),
                "messageCount", llmRequest.messages().size(),
                "stream", false
        ));
        try {
            LlmResponse response = llmService.generate(llmRequest);
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("model", llmRequest.modelEndpoint().model());
            tracePayload.put("finishReason", Objects.toString(response.finishReason(), ""));
            tracePayload.put("answerLength", response.content() == null ? 0 : response.content().length());
            tracePayload.put("answerContent", Objects.toString(response.content(), ""));
            appendLlmRequestTraceFields(tracePayload, llmRequest);
            tracePayload.put("firstTokenMs", -1);
            tracePayload.put("elapsedMs", durationMs(start));
            tracePayload.put("stream", false);
            recordTrace("answer_generation", "answer_generation.summary", tracePayload);
            recordAnswerReferences(response.content(), referenceSources);
            return response;
        } catch (RagServiceException ex) {
            recordTrace("answer_generation", "answer_generation.failed", Map.of(
                    "errorCode", ex.errorCode().name(),
                    "message", summarize(ex),
                    "elapsedMs", durationMs(start),
                    "stream", false
            ));
            throw ex;
        } catch (RuntimeException ex) {
            recordTrace("answer_generation", "answer_generation.failed", Map.of(
                    "errorCode", RagErrorCode.LLM_UNAVAILABLE.name(),
                    "message", summarize(ex),
                    "elapsedMs", durationMs(start),
                    "stream", false
            ));
            throw new RagServiceException(
                    RagErrorCode.LLM_UNAVAILABLE,
                    "答案生成失败: " + summarize(ex),
                    ex
            );
        }
    }

    private static String summarize(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = normalizeStatic(root.getMessage());
        return message != null ? message : root.getClass().getSimpleName();
    }

    private static String normalizeStatic(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void handleStreamChunk(
            LlmStreamChunk chunk,
            StringBuilder answerBuilder,
            AtomicReference<String> finishReasonHolder,
            AtomicReference<Map<String, Object>> metadataHolder,
            PreparedAnswer preparedAnswer,
            Consumer<StreamEvent> eventConsumer,
            RagProcessEventPublisher eventPublisher,
            StageHandle generationHandle,
            AtomicBoolean firstTokenEmitted,
            AtomicReference<Long> firstTokenMsHolder,
            ReferenceSectionSuppressor referenceSectionSuppressor,
            long generationStart
    ) {
        if (!chunk.delta().isEmpty()) {
            if (firstTokenEmitted.compareAndSet(false, true)) {
                firstTokenMsHolder.set(durationMs(generationStart));
                eventPublisher.milestone(generationHandle, "first_token", Map.of());
                recordTrace("answer_generation", "answer_generation.first_token", Map.of(
                        "requestId", preparedAnswer.retrieval().requestId()
                ));
            }
            String visibleDelta = referenceSectionSuppressor.append(chunk.delta());
            if (!visibleDelta.isEmpty()) {
                answerBuilder.append(visibleDelta);
                eventConsumer.accept(new StreamEvent("delta", new ApiModels.RagAnswerStreamDelta(visibleDelta)));
            }
        }
        if (!chunk.metadata().isEmpty()) {
            metadataHolder.set(chunk.metadata());
        }
        if (chunk.completed()) {
            flushStreamAnswerTail(answerBuilder, referenceSectionSuppressor, preparedAnswer, eventConsumer);
            finishReasonHolder.set(chunk.finishReason());
            log.info("流式问答处理完成，请求ID={}，结束原因={}，结果块数={}",
                    preparedAnswer.retrieval().requestId(),
                    chunk.finishReason(),
                    preparedAnswer.retrieval().chunks().size());
            Map<String, Object> metadata = new LinkedHashMap<>(metadataHolder.get());
            metadata.put("llm_model_id", preparedAnswer.llmModel().modelId());
            eventPublisher.complete(generationHandle, answerGenerationDetails(chunk.finishReason(), metadata));
            Map<String, Object> tracePayload = new LinkedHashMap<>(answerGenerationDetails(chunk.finishReason(), metadata));
            tracePayload.put("requestId", preparedAnswer.retrieval().requestId());
            tracePayload.put("model", preparedAnswer.llmRequest().modelEndpoint().model());
            tracePayload.put("answerLength", answerBuilder.length());
            tracePayload.put("answerContent", answerBuilder.toString());
            appendLlmRequestTraceFields(tracePayload, preparedAnswer.llmRequest());
            tracePayload.put("firstTokenMs", Objects.requireNonNullElse(firstTokenMsHolder.get(), -1L));
            tracePayload.put("stream", true);
            tracePayload.put("elapsedMs", durationMs(generationStart));
            recordTrace("answer_generation", "answer_generation.summary", tracePayload);
            recordAnswerReferences(answerBuilder.toString(), preparedAnswer.referenceSources());
            eventConsumer.accept(new StreamEvent("done", new ApiModels.RagAnswerStreamDone(
                    chunk.finishReason(),
                    Map.copyOf(metadata)
            )));
        }
    }

    private void flushStreamAnswerTail(
            StringBuilder answerBuilder,
            ReferenceSectionSuppressor referenceSectionSuppressor,
            PreparedAnswer preparedAnswer,
            Consumer<StreamEvent> eventConsumer
    ) {
        String visibleTail = referenceSectionSuppressor.finish();
        if (!visibleTail.isEmpty()) {
            answerBuilder.append(visibleTail);
            eventConsumer.accept(new StreamEvent("delta", new ApiModels.RagAnswerStreamDelta(visibleTail)));
        }
        emitReferenceEvent(answerBuilder.toString(), preparedAnswer.referenceSources(), eventConsumer);
    }

    /**
     * 以独立的 {@code reference} 事件下发参考资料，避免把参考资料混进 {@code delta} 正文。
     *
     * <p>载荷为 {@link ApiModels.RagReferenceEvent}，字段为 {@code fileName}、{@code path}、
     * {@code documentId}、{@code datasetName}、{@code chunkIds}；答案为空或与问题无关时下发空列表。
     */
    private static void emitReferenceEvent(
            String answer,
            List<ReferenceSource> referenceSources,
            Consumer<StreamEvent> eventConsumer
    ) {
        eventConsumer.accept(new StreamEvent(
                "reference",
                new ApiModels.RagReferenceEvent(referencesForAnswer(answer, referenceSources))
        ));
    }

    /**
     * 与下发给客户端的 {@code reference} 事件同一口径的参考资料列表：
     * 答案为空或判定为与问题无关时返回空列表。trace 持久化与 SSE 下发共用，保证两边一致。
     */
    private static List<ApiModels.Reference> referencesForAnswer(
            String answer,
            List<ReferenceSource> referenceSources
    ) {
        return shouldOmitBackendReferences(answer)
                ? List.of()
                : referencesFromSources(referenceSources);
    }

    /**
     * 以独立 trace 事件 {@code answer_generation.references} 持久化参考资料，
     * 与 SSE 的 {@code reference} 独立事件对称；经 trace_id/request_id 与 summary 事件关联。
     */
    private void recordAnswerReferences(String answer, List<ReferenceSource> referenceSources) {
        recordTrace("answer_generation", "answer_generation.references", Map.of(
                "references", referencesForAnswer(answer, referenceSources)
        ));
    }

    private static Map<String, Object> answerGenerationDetails(
            String finishReason,
            Map<String, Object> metadata
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (finishReason != null && !finishReason.isBlank()) {
            details.put("finishReason", finishReason);
        }
        copyIfPresent(metadata, details, "prompt_tokens", "promptTokens");
        copyIfPresent(metadata, details, "completion_tokens", "completionTokens");
        copyIfPresent(metadata, details, "total_tokens", "totalTokens");
        return Map.copyOf(details);
    }

    private static String lastUserPrompt(LlmRequest llmRequest) {
        List<LlmMessage> messages = llmRequest.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message.role() == LlmMessageRole.USER) {
                return Objects.toString(message.content(), "");
            }
        }
        return "";
    }

    private static String systemPrompt(LlmRequest llmRequest) {
        for (LlmMessage message : llmRequest.messages()) {
            if (message.role() == LlmMessageRole.SYSTEM) {
                return Objects.toString(message.content(), "");
            }
        }
        return "";
    }

    private static void appendLlmRequestTraceFields(Map<String, Object> payload, LlmRequest llmRequest) {
        payload.put("systemPrompt", systemPrompt(llmRequest));
        payload.put("userPrompt", lastUserPrompt(llmRequest));
        payload.put("memoryEnabled", llmRequest.conversationId() != null && !llmRequest.conversationId().isBlank());
        payload.put("conversationId", Objects.toString(llmRequest.conversationId(), ""));
        payload.put("baseMessageCount", llmRequest.messages().size());
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey
    ) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    /** 组装最终问答响应，并附带检索链路相关元数据。 */
    private ApiModels.RagAnswerResponse toRagAnswerResponse(
            String answer,
            PreparedAnswer preparedAnswer
    ) {
        String normalizedAnswer = removeReferenceSection(answer);
        List<ApiModels.Reference> references = shouldOmitBackendReferences(normalizedAnswer)
                ? List.of()
                : referencesFromSources(preparedAnswer.referenceSources());
        return new ApiModels.RagAnswerResponse(normalizedAnswer, references);
    }

    private static String removeReferenceSection(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        java.util.regex.Matcher matcher = REFERENCE_HEADING_PATTERN.matcher(answer);
        if (!matcher.find()) {
            return answer;
        }
        return trimTrailingWhitespace(answer.substring(0, matcher.start()));
    }

    private static String trimTrailingWhitespace(String value) {
        return value.replaceFirst("\\s+$", "");
    }

    private static boolean shouldOmitBackendReferences(String answer) {
        return answer == null || answer.isBlank() || answer.contains(UNRELATED_ANSWER_MESSAGE);
    }

    private static List<ApiModels.Reference> referencesFromSources(List<ReferenceSource> sources) {
        return sources.stream()
                .map(source -> new ApiModels.Reference(
                        source.fileName(),
                        source.path(),
                        source.documentId(),
                        source.datasetName(),
                        source.chunkIds()
                ))
                .toList();
    }

    private List<ApiModels.Reference> buildReferences(List<RetrievedChunk> chunks) {
        String difyFilesUrl = appProperties.getRag().getDifyFilesUrl();
        Map<String, DocumentMeta> documentMetas = resolveDocumentMetas(chunks);
        Map<String, String> datasetNames = resolveDatasetNames();
        Map<String, List<String>> chunkIdsByDocument = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            List<String> chunkIds = chunkIdsByDocument.computeIfAbsent(chunk.documentId(), ignored -> new ArrayList<>());
            if (!chunkIds.contains(chunk.chunkId())) {
                chunkIds.add(chunk.chunkId());
            }
        }
        return chunks.stream()
                .filter(chunk -> {
                    Map<String, Object> metadata = chunk.metadata();
                    DocumentMeta documentMeta = documentMetas.get(chunk.documentId());
                    return metadata.containsKey("document_name")
                            || metadata.containsKey("upload_file_id")
                            || documentMeta != null;
                })
                .map(chunk -> {
                    Map<String, Object> metadata = chunk.metadata();
                    DocumentMeta documentMeta = documentMetas.get(chunk.documentId());
                    String fileName = firstNonBlank(
                            metadataValue(metadata, "document_name"),
                            documentMeta == null ? null : documentMeta.name()
                    );
                    String uploadFileId = firstNonBlank(
                            metadataValue(metadata, "upload_file_id"),
                            documentMeta == null ? null : documentMeta.uploadFileId()
                    );
                    String path = buildFilePath(
                            difyFilesUrl,
                            uploadFileId,
                            documentMeta == null ? null : documentMeta.uploadFileKey(),
                            fileName
                    );
                    path = resolveReferencePath(documentMeta, path);
                    return new ApiModels.Reference(
                            fileName,
                            path,
                            chunk.documentId(),
                            resolveDatasetName(datasetNames, chunk, documentMeta),
                            List.copyOf(chunkIdsByDocument.getOrDefault(chunk.documentId(), List.of()))
                    );
                })
                .filter(reference -> reference.fileName() != null)
                .distinct()
                .toList();
    }

    private static String resolveReferencePath(DocumentMeta documentMeta, String defaultPath) {
        if (documentMeta == null
                || !CONFLUENCE_SOURCE_TYPE.equalsIgnoreCase(documentMeta.sourceType())
                || documentMeta.referenceUrl() == null) {
            return defaultPath;
        }
        return documentMeta.referenceUrl();
    }

    private String buildFilePath(String difyFilesUrl, String uploadFileId, String uploadFileKey, String fileName) {
        if (difyFilesUrl == null || fileName == null) {
            return null;
        }
        String storageFileName = storageFileName(uploadFileKey);
        if (storageFileName != null) {
            String base = normalizeFileUrlBase(difyFilesUrl);
            if (base == null) {
                return null;
            }
            return base + "/" + storageFileName;
        }
        if (uploadFileId == null) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex >= 0 ? fileName.substring(dotIndex + 1) : "";
        String base = normalizeFileUrlBase(difyFilesUrl);
        if (base == null) {
            return null;
        }
        return extension.isEmpty()
                ? base + "/" + uploadFileId
                : base + "/" + uploadFileId + "." + extension;
    }

    private static String normalizeFileUrlBase(String difyFilesUrl) {
        String normalized = normalizeStatic(difyFilesUrl);
        if (normalized == null) {
            return null;
        }
        if (hasRepeatedUrlScheme(normalized)) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_CONFIGURATION,
                    "app.rag.dify-files-url 配置非法，存在重复协议: " + normalized
            );
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean hasRepeatedUrlScheme(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://http://")
                || lower.startsWith("https://https://")
                || lower.startsWith("http://https://")
                || lower.startsWith("https://http://");
    }

    private static String storageFileName(String uploadFileKey) {
        String normalized = normalizeStatic(uploadFileKey);
        if (normalized == null) {
            return null;
        }
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalizeStatic(normalized.substring(slashIndex + 1)) : normalized;
    }

    private void recordTrace(String stage, String eventName, Map<String, Object> payload) {
        if (!traceRecorder.enabled()) {
            return;
        }
        if (!shouldRecordTraceEvent(eventName)) {
            return;
        }
        traceRecorder.record(
                Objects.toString(MDC.get("traceId"), "unknown"),
                Objects.toString(payload.get("requestId"), null),
                "app",
                stage,
                eventName,
                payload
        );
    }

    private boolean shouldRecordTraceEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        if (eventName.endsWith(".failed") || eventName.contains(".failed") || eventName.endsWith(".fallback")) {
            return true;
        }
        return switch (traceProperties.getMode()) {
            case OFF -> false;
            case SUMMARY -> SUMMARY_TRACE_EVENTS.contains(eventName);
            case DETAIL, DEBUG -> true;
        };
    }

    private static Map<String, Object> planningPayload(ExecutionPlan plan, long elapsedMs) {
        RetrievalPlan primary = plan.primaryRetrievalPlan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planType", plan.planType().name());
        payload.put("retrievalPlanCount", plan.retrievalPlans().size());
        payload.put("primaryKbCount", primary.recallSpecs().size());
        payload.put("topK", primary.topK());
        payload.put("rankingMode", primary.rankingSpec().getClass().getSimpleName());
        payload.put("embeddingModel", primary.embeddingSpec().modelEndpoint().model());
        if (primary.rankingSpec() instanceof RankingSpec.RerankRankingSpec rerankSpec) {
            payload.put("rerankModel", rerankSpec.modelEndpoint().model());
        }
        payload.put("elapsedMs", elapsedMs);
        return Map.copyOf(payload);
    }

    private Map<String, Object> requestReceivedPayload(
            String endpointType,
            String query,
            List<String> docIds,
            Object planType,
            Boolean queryRewriteEnabled,
            String conversationId,
            boolean stream
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpointType", endpointType);
        payload.put("originalQuery", query);
        payload.put("queryLength", query.length());
        payload.put("docFilterCount", docIds.size());
        payload.put("docFilterKnowledgeBases", resolveDocFilterKnowledgeBasesForTrace(docIds));
        payload.put("conversationId", Objects.toString(conversationId, ""));
        payload.put("planType", Objects.toString(planType, "default"));
        payload.put("queryRewriteEnabled", Objects.toString(queryRewriteEnabled, "default"));
        payload.put("stream", stream);
        return Map.copyOf(payload);
    }

    private List<Map<String, Object>> resolveDocFilterKnowledgeBasesForTrace(List<String> docIds) {
        if (docIds.isEmpty()) {
            return List.of();
        }
        try {
            return metadataQueryService.listKnowledgeBases(new KnowledgeBaseQueryCondition(docIds))
                    .stream()
                    .map(knowledgeBase -> Map.<String, Object>of(
                            "knowledgeBaseId", knowledgeBase.knowledgeBaseId(),
                            "name", knowledgeBase.name()
                    ))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("trace解析docIds对应知识库失败 | docCount={}, error={}", docIds.size(), summarize(ex));
            return List.of();
        }
    }

    private static long durationMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    public record StreamEvent(String name, Object payload) {
    }

    private record PreparedAnswer(
            ExecutionPlan executionPlan,
            RetrievalResult retrieval,
            ModelMeta llmModel,
            LlmRequest llmRequest,
            List<ReferenceSource> referenceSources
    ) {
    }

    private record AgenticExecutionResult(
            UUID runId,
            String answer,
            List<ApiModels.Reference> references,
            JsonNode output
    ) {
    }

    private record ReferenceSource(
            int index,
            String key,
            String fileName,
            String path,
            String documentId,
            String datasetName,
            List<String> chunkIds
    ) {
    }

    private record QueryRewriteResult(
            boolean dependsOnHistory,
            String resolvedQuestion,
            List<String> retrievalQueries
    ) {
        private QueryRewriteResult {
            resolvedQuestion = normalizeStatic(resolvedQuestion);
            retrievalQueries = distinctNonBlank(retrievalQueries);
        }

        static QueryRewriteResult fallback() {
            return new QueryRewriteResult(false, null, List.of());
        }

        List<String> executedQueries(String originalQuery) {
            if (dependsOnHistory) {
                if (!retrievalQueries.isEmpty()) {
                    return retrievalQueries;
                }
                String resolved = normalizeStatic(resolvedQuestion);
                return resolved == null ? List.of(originalQuery) : List.of(resolved);
            }
            List<String> queries = new ArrayList<>();
            queries.add(originalQuery);
            queries.addAll(retrievalQueries);
            return distinctNonBlank(queries);
        }

        int executedQueryCount(String originalQuery) {
            return executedQueries(originalQuery).size();
        }

        String rerankQuery(String originalQuery) {
            if (!dependsOnHistory) {
                return originalQuery;
            }
            String resolved = normalizeStatic(resolvedQuestion);
            if (resolved != null) {
                return resolved;
            }
            return retrievalQueries.isEmpty() ? originalQuery : retrievalQueries.get(0);
        }

        private static List<String> distinctNonBlank(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            Map<String, String> byText = new LinkedHashMap<>();
            for (String value : values) {
                String normalized = normalizeStatic(value);
                if (normalized != null) {
                    byText.putIfAbsent(normalized, normalized);
                }
            }
            return List.copyOf(byText.values());
        }
    }

    private static final class ReferenceSectionSuppressor {

        private final StringBuilder pending = new StringBuilder();
        private boolean suppressing;

        String append(String delta) {
            if (suppressing || delta == null || delta.isEmpty()) {
                return "";
            }
            pending.append(delta);
            java.util.regex.Matcher matcher = REFERENCE_HEADING_PATTERN.matcher(pending);
            if (matcher.find()) {
                String visible = pending.substring(0, matcher.start());
                pending.setLength(0);
                suppressing = true;
                return visible;
            }
            int emitLength = safeEmitLength();
            if (emitLength == 0) {
                return "";
            }
            String visible = pending.substring(0, emitLength);
            pending.delete(0, emitLength);
            return visible;
        }

        private int safeEmitLength() {
            int lastLineBreak = Math.max(pending.lastIndexOf("\n"), pending.lastIndexOf("\r"));
            if (lastLineBreak >= 0) {
                return lastLineBreak + 1;
            }
            return Math.max(0, pending.length() - REFERENCE_SECTION_DETECTION_GUARD_CHARS);
        }

        String finish() {
            if (suppressing || pending.isEmpty()) {
                pending.setLength(0);
                return "";
            }
            String visible = pending.toString();
            pending.setLength(0);
            return visible;
        }
    }
}
