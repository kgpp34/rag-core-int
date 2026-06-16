package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

class MilvusExceptionSupportTest {

    @Test
    void retrievalFailed_buildsUnifiedMilvusErrorMessage() {
        RuntimeException cause = new RuntimeException("boom");

        RagServiceException ex = MilvusExceptionSupport.retrievalFailed(
                "dense ",
                "kb-1",
                "collection-1",
                cause
        );

        assertThat(ex.errorCode()).isEqualTo(RagErrorCode.RETRIEVAL_FAILED);
        assertThat(ex).hasMessage("Milvus dense 检索失败，knowledgeBaseId=kb-1，collection=collection-1，原因=boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void summarize_returnsSimpleClassNameWhenMessageIsBlank() {
        assertThat(MilvusExceptionSupport.summarize(new RuntimeException("")))
                .isEqualTo("RuntimeException");
    }
}
