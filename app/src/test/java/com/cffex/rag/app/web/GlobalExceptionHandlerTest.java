package com.cffex.rag.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import org.springframework.http.ResponseEntity;

import com.cffex.rag.app.api.ApiModels;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

import jakarta.servlet.http.HttpServletRequest;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRagServiceException_returnsStructuredErrorResponse() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/rag/answer");

        ResponseEntity<ApiModels.ErrorResponse> response = handler.handleRagServiceException(
                new RagServiceException(RagErrorCode.RETRIEVAL_FAILED, "知识检索失败: milvus timeout"),
                request
        );
        assertNotNull(response.getBody());
        ApiModels.ErrorResponse body = response.getBody();

        assertEquals(502, response.getStatusCode().value());
        assertEquals("RETRIEVAL_FAILED", body.code());
        assertEquals("知识检索失败: milvus timeout", body.message());
        assertTrue(body.retryable());
    }
}
