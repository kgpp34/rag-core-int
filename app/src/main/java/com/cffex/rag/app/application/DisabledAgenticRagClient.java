package com.cffex.rag.app.application;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;

/** Test/default adapter used by the legacy constructor; production injects the HTTP adapter. */
final class DisabledAgenticRagClient implements AgenticRagClient {

    @Override
    public UUID createRun(List<AgenticMessage> messages, List<String> docIds, String requestId) {
        throw unsupported();
    }

    @Override
    public void streamEvents(UUID runId, Consumer<AgenticEvent> eventConsumer, String requestId) {
        throw unsupported();
    }

    @Override
    public AgenticRunResult getRun(UUID runId, String requestId) {
        throw unsupported();
    }

    @Override
    public void cancelRun(UUID runId, String requestId) {
        // Nothing to cancel.
    }

    private static RagServiceException unsupported() {
        return new RagServiceException(RagErrorCode.PLAN_UNSUPPORTED, "测试构造器未配置 Agentic RAG 客户端");
    }
}
