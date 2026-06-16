package com.cffex.rag.app.web;

import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局异常处理器。
 *
 * <p>负责把常见业务异常映射为统一错误响应，并在日志中记录异常上下文。
 */
@RestControllerAdvice
@Hidden
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", false, message, request, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleBadRequest(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", false, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(RagServiceException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleRagServiceException(
            RagServiceException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(ex.errorCode().httpStatus());
        HttpStatus effectiveStatus = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        return buildResponse(
                effectiveStatus,
                ex.errorCode().name(),
                ex.retryable(),
                ex.getMessage(),
                request,
                ex
        );
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleUnsupported(
            UnsupportedOperationException ex,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, RagErrorCode.PLAN_UNSUPPORTED.name(), false, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, RagErrorCode.INTERNAL_ERROR.name(), false, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleNotFound(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", false, "资源不存在", request, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                RagErrorCode.INTERNAL_ERROR.name(),
                false,
                "服务内部异常: " + rootMessage(ex),
                request,
                ex
        );
    }

    private ResponseEntity<ApiModels.ErrorResponse> buildResponse(
            HttpStatus status,
            String code,
            boolean retryable,
            String message,
            HttpServletRequest request,
            Exception ex
    ) {
        String traceId = Objects.toString(MDC.get(TraceLoggingFilter.TRACE_ID_KEY), "");
        if (status.is5xxServerError()) {
            log.error("请求处理失败，状态码={}，错误码={}，路径={}，traceId={}，根因={}",
                    status.value(),
                    code,
                    request.getRequestURI(),
                    traceId,
                    rootMessage(ex),
                    ex);
        } else {
            log.warn("请求处理失败，状态码={}，错误码={}，路径={}，traceId={}，原因={}",
                    status.value(),
                    code,
                    request.getRequestURI(),
                    traceId,
                    message);
        }
        return ResponseEntity.status(status).body(new ApiModels.ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                traceId,
                retryable
        ));
    }

    private String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = Objects.toString(root.getMessage(), "").trim();
        return message.isEmpty() ? root.getClass().getSimpleName() : message;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + Objects.toString(error.getDefaultMessage(), "参数不合法");
    }
}
