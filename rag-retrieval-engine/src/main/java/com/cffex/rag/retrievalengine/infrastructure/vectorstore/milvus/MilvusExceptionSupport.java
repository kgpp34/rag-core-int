package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

/**
 * Milvus 基础设施异常支持：统一错误码、文案模板与异常摘要。
 */
public final class MilvusExceptionSupport {

    private MilvusExceptionSupport() {
    }

    public static RagServiceException retrievalFailed(
            String routeLabel,
            String knowledgeBaseId,
            String collectionName,
            RuntimeException cause
    ) {
        return new RagServiceException(
                RagErrorCode.RETRIEVAL_FAILED,
                "Milvus %s检索失败，knowledgeBaseId=%s，collection=%s，原因=%s"
                        .formatted(routeLabel, knowledgeBaseId, collectionName, summarize(cause)),
                cause
        );
    }

    public static String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
