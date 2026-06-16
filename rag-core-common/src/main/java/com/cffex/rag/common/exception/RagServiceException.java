package com.cffex.rag.common.exception;

import java.util.Objects;

/**
 * RAG 链路统一运行时异常。
 *
 * <p>保留可机读的错误码与重试语义，便于上层做统一响应映射和告警聚合。
 */
public class RagServiceException extends RuntimeException {

    private final RagErrorCode errorCode;

    public RagServiceException(RagErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public RagServiceException(RagErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public RagErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return errorCode.retryable();
    }
}
