package com.cffex.rag.retrievalengine.infrastructure.vectorstore.kingbase;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

/**
 * Kingbase 基础设施异常支持：统一错误码、文案模板与异常摘要。
 */
public final class KingbaseExceptionSupport {

    private KingbaseExceptionSupport() {
    }

    public static RagServiceException retrievalFailed(
            String routeLabel,
            String knowledgeBaseId,
            String collectionName,
            RuntimeException cause
    ) {
        return new RagServiceException(
                RagErrorCode.RETRIEVAL_FAILED,
                "Kingbase %s检索失败，knowledgeBaseId=%s，collection=%s，原因=%s"
                        .formatted(routeLabel, knowledgeBaseId, collectionName, summarize(cause)),
                cause
        );
    }

    public static RagServiceException invalidCollectionName(String collectionName) {
        return new RagServiceException(
                RagErrorCode.INVALID_CONFIGURATION,
                "非法的 Kingbase collection 名称（只允许字母/数字/下划线）：%s".formatted(collectionName)
        );
    }

    public static RagServiceException metadataParseFailed(String payload, Exception cause) {
        return new RagServiceException(
                RagErrorCode.RETRIEVAL_FAILED,
                "Kingbase 候选结果 metadata 解析失败：%s".formatted(summarize(cause)),
                cause
        );
    }

    public static String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
