package com.cffex.rag.common.exception;

/**
 * RAG 链路统一错误码。
 *
 * <p>错误码用于跨模块传递故障语义，避免不同层级只暴露笼统的运行时异常。
 */
public enum RagErrorCode {
    INVALID_REQUEST(400, false),
    PLAN_UNSUPPORTED(400, false),
    INVALID_CONFIGURATION(500, false),
    PLANNING_FAILED(500, false),
    INTENT_RECOGNITION_FAILED(500, true),
    RETRIEVAL_FAILED(502, true),
    LLM_REQUEST_REJECTED(502, false),
    LLM_UNAVAILABLE(503, true),
    STREAM_WRITE_FAILED(500, false),
    INTERNAL_ERROR(500, false);

    private final int httpStatus;
    private final boolean retryable;

    RagErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
