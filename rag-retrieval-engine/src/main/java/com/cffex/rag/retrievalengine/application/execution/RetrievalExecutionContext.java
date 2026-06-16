package com.cffex.rag.retrievalengine.application.execution;

import java.util.Objects;

import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.domain.model.RetrievalSession;

/**
 * 检索执行上下文。
 *
 * <p>承载本模块自己的 {@link ExecuteRetrievalCommand}，避免共享模型继续深入执行主链。
 */
public record RetrievalExecutionContext(
        ExecuteRetrievalCommand command,
        RetrievalSession session,
        float[] queryVector,
        RetrievalTrace trace,
        RagProcessEventPublisher eventPublisher
) {

    public RetrievalExecutionContext(ExecuteRetrievalCommand command) {
        this(command, RagProcessEventPublisher.NO_OP);
    }

    public RetrievalExecutionContext(ExecuteRetrievalCommand command, RagProcessEventPublisher eventPublisher) {
        this(
                command,
                RetrievalSession.start(
                        command.queryText(),
                        command.recallCommands().stream().map(recallCommand -> recallCommand.recallPolicy()).toList(),
                        command.globalRankingPolicy()
                ),
                null,
                new RetrievalTrace(),
                eventPublisher
        );
    }

    public RetrievalExecutionContext {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(trace, "trace must not be null");
        Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        queryVector = queryVector == null ? null : queryVector.clone();
    }

    public RetrievalExecutionContext withSession(RetrievalSession session) {
        return new RetrievalExecutionContext(command, session, queryVector, trace, eventPublisher);
    }

    public RetrievalExecutionContext withQueryVector(float[] queryVector) {
        return new RetrievalExecutionContext(command, session, queryVector, trace, eventPublisher);
    }

    @Override
    public float[] queryVector() {
        return queryVector == null ? null : queryVector.clone();
    }
}
