package com.cffex.rag.app.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.MDC;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.common.service.RagProcessEventPublisher;

/** 将请求级 RAG 流程事件转换为 SSE {@code rag_progress} 事件。 */
final class SseRagProcessEventPublisher implements RagProcessEventPublisher {

    private static final String TRACE_ID_KEY = "traceId";

    private final Consumer<RagApiService.StreamEvent> eventConsumer;
    private final String traceId;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong eventIdSequence = new AtomicLong();

    SseRagProcessEventPublisher(Consumer<RagApiService.StreamEvent> eventConsumer) {
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
        this.traceId = MDC.get(TRACE_ID_KEY);
    }

    @Override
    public StageHandle start(RagProcessStage stage, Map<String, Object> details) {
        StageHandle handle = new StageHandle(
                stageCode(stage) + "-" + eventIdSequence.incrementAndGet(),
                stage,
                System.nanoTime()
        );
        publish(handle, "started", startedTitle(stage), null, details);
        return handle;
    }

    @Override
    public void milestone(StageHandle handle, String milestone, Map<String, Object> details) {
        Map<String, Object> mergedDetails = new LinkedHashMap<>(safeDetails(details));
        mergedDetails.put("milestone", milestone);
        publish(handle, "milestone", milestoneTitle(handle.stage(), milestone), elapsedMs(handle), mergedDetails);
    }

    @Override
    public void complete(StageHandle handle, Map<String, Object> details) {
        publish(handle, "completed", completedTitle(handle.stage()), elapsedMs(handle), details);
    }

    @Override
    public void fallback(StageHandle handle, Map<String, Object> details) {
        publish(handle, "fallback", fallbackTitle(handle.stage()), elapsedMs(handle), details);
    }

    @Override
    public void fail(StageHandle handle, String errorCode) {
        publish(handle, "failed", failedTitle(handle.stage()), elapsedMs(handle), Map.of("errorCode", errorCode));
    }

    private void publish(
            StageHandle handle,
            String status,
            String title,
            Long elapsedMs,
            Map<String, Object> details
    ) {
        ApiModels.RagProgressEvent payload = new ApiModels.RagProgressEvent(
                handle.eventId(),
                sequence.incrementAndGet(),
                traceId,
                Instant.now(),
                stageCode(handle.stage()),
                status,
                title,
                elapsedMs,
                safeDetails(details)
        );
        eventConsumer.accept(new RagApiService.StreamEvent("rag_progress", payload));
    }

    private static long elapsedMs(StageHandle handle) {
        return (System.nanoTime() - handle.startNanoTime()) / 1_000_000;
    }

    private static Map<String, Object> safeDetails(Map<String, Object> details) {
        return details == null || details.isEmpty() ? Map.of() : Map.copyOf(details);
    }

    private static String stageCode(RagProcessStage stage) {
        return stage.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String startedTitle(RagProcessStage stage) {
        return switch (stage) {
            case QUERY_REWRITE -> "正在改写检索问题";
            case RETRIEVAL -> "正在检索知识库";
            case RERANK -> "正在对检索结果重排序";
            case ANSWER_GENERATION -> "正在生成答案";
        };
    }

    private static String completedTitle(RagProcessStage stage) {
        return switch (stage) {
            case QUERY_REWRITE -> "检索问题改写完成";
            case RETRIEVAL -> "知识库检索完成";
            case RERANK -> "检索结果重排序完成";
            case ANSWER_GENERATION -> "答案生成完成";
        };
    }

    private static String fallbackTitle(RagProcessStage stage) {
        return switch (stage) {
            case QUERY_REWRITE -> "检索问题改写未完成，已使用原始问题继续检索";
            default -> completedTitle(stage);
        };
    }

    private static String failedTitle(RagProcessStage stage) {
        return switch (stage) {
            case QUERY_REWRITE -> "检索问题改写失败";
            case RETRIEVAL -> "知识库检索失败";
            case RERANK -> "检索结果重排序失败";
            case ANSWER_GENERATION -> "答案生成失败";
        };
    }

    private static String milestoneTitle(RagProcessStage stage, String milestone) {
        if (stage == RagProcessStage.ANSWER_GENERATION && "first_token".equals(milestone)) {
            return "答案开始生成";
        }
        return startedTitle(stage);
    }
}
