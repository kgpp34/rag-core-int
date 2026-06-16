package com.cffex.rag.common.service;

import java.util.Map;
import java.util.Objects;

/**
 * RAG 执行流程事件发布端口。
 *
 * <p>该接口不依赖 SSE 或 Web 类型，调用方可以将事件转发到 SSE、Trace 或其他通道。
 */
public interface RagProcessEventPublisher {

    RagProcessEventPublisher NO_OP = new RagProcessEventPublisher() {
    };

    default StageHandle start(RagProcessStage stage, Map<String, Object> details) {
        return new StageHandle("", stage, System.nanoTime());
    }

    default void milestone(StageHandle handle, String milestone, Map<String, Object> details) {
    }

    default void complete(StageHandle handle, Map<String, Object> details) {
    }

    default void fallback(StageHandle handle, Map<String, Object> details) {
    }

    default void fail(StageHandle handle, String errorCode) {
    }

    enum RagProcessStage {
        QUERY_REWRITE,
        RETRIEVAL,
        RERANK,
        ANSWER_GENERATION
    }

    record StageHandle(String eventId, RagProcessStage stage, long startNanoTime) {
        public StageHandle {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            stage = Objects.requireNonNull(stage, "stage must not be null");
        }
    }
}
