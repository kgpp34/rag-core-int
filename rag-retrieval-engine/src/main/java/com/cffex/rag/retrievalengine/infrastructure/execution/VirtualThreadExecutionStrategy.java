package com.cffex.rag.retrievalengine.infrastructure.execution;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.application.port.ParallelExecutionStrategy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 基于虚拟线程的并行执行策略，适合 I/O 密集型检索场景。 */
@Component
public class VirtualThreadExecutionStrategy implements ParallelExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadExecutionStrategy.class);

    @Override
    public <T> List<T> invokeAll(Collection<? extends Callable<T>> tasks) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 统一提交后等待完成，避免调用方关心线程细节
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            List<Throwable> failures = new ArrayList<>();
            int taskIndex = 0;
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    failures.add(cause);
                    log.warn("并发召回任务执行失败，taskIndex={}，taskCount={}，原因类型={}，原因={}",
                            taskIndex,
                            futures.size(),
                            cause.getClass().getSimpleName(),
                            summarize(cause));
                }
                taskIndex++;
            }
            if (!failures.isEmpty() && !results.isEmpty()) {
                log.warn("并发召回出现部分失败，成功任务数={}，失败任务数={}，将继续使用成功结果",
                        results.size(),
                        failures.size());
            }
            if (results.isEmpty() && !failures.isEmpty()) {
                Throwable firstFailure = failures.get(0);
                if (firstFailure instanceof RagServiceException ragServiceException) {
                    throw ragServiceException;
                }
                throw new RagServiceException(
                        RagErrorCode.RETRIEVAL_FAILED,
                        "并发召回全部失败，任务数=%d，首个原因=%s".formatted(futures.size(), summarize(firstFailure)),
                        firstFailure
                );
            }
            return List.copyOf(results);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RagServiceException(
                    RagErrorCode.RETRIEVAL_FAILED,
                    "并发召回被中断，请稍后重试",
                    ex
            );
        }
    }

    private static String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
