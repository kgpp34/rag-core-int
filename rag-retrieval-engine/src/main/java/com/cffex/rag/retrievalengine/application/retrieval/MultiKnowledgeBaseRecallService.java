package com.cffex.rag.retrievalengine.application.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;
import com.cffex.rag.retrievalengine.application.execution.RetrievalTrace;
import com.cffex.rag.retrievalengine.application.port.ParallelExecutionStrategy;
import com.cffex.rag.retrievalengine.application.query.ProcessedQuery;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.common.service.RagProcessEventPublisher;

/**
 * 多知识库组合召回服务。
 *
 * <p>这是边界收缩阶段新增的内部召回服务，执行主链直接消费候选对象，不再先转成
 * Spring AI Document 再转回 RetrievalCandidate。
 */
@Component
public class MultiKnowledgeBaseRecallService {

    private static final Logger log = LoggerFactory.getLogger(MultiKnowledgeBaseRecallService.class);

    private final ParallelExecutionStrategy parallelExecutionStrategy;
    private final KnowledgeBaseRetrievalTask knowledgeBaseRetrievalTask;

    public MultiKnowledgeBaseRecallService(
            ParallelExecutionStrategy parallelExecutionStrategy,
            KnowledgeBaseRetrievalTask knowledgeBaseRetrievalTask
    ) {
        this.parallelExecutionStrategy = Objects.requireNonNull(parallelExecutionStrategy);
        this.knowledgeBaseRetrievalTask = Objects.requireNonNull(knowledgeBaseRetrievalTask);
    }

    public List<RetrievalCandidate> recall(ProcessedQuery query, RetrievalExecutionContext context) {
        ExecuteRetrievalCommand command = context.command();
        RetrievalTrace trace = context.trace();

        List<Callable<List<RetrievalCandidate>>> tasks = command.recallCommands().stream()
                .<Callable<List<RetrievalCandidate>>>map(spec -> () -> context.eventPublisher() == RagProcessEventPublisher.NO_OP
                        ? knowledgeBaseRetrievalTask.execute(query.text(), context.queryVector(), spec, trace)
                        : knowledgeBaseRetrievalTask.execute(
                                query.text(), context.queryVector(), spec, trace, context.eventPublisher()))
                .toList();

        List<RetrievalCandidate> candidates = parallelExecutionStrategy.invokeAll(tasks).stream()
                .flatMap(List::stream)
                .toList();

        log.debug("多知识库召回完成 | kbCount={}, candidateCount={}",
                context.session().recallPolicies().size(),
                candidates.size());
        return candidates;
    }
}
