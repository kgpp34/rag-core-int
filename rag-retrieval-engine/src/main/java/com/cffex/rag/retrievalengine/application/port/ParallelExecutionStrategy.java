package com.cffex.rag.retrievalengine.application.port;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/** 并行执行抽象，屏蔽线程模型与执行器实现细节。 */
public interface ParallelExecutionStrategy {
    /** 并行执行任务集合，并按提交顺序返回结果。 */
    <T> List<T> invokeAll(Collection<? extends Callable<T>> tasks);
}
