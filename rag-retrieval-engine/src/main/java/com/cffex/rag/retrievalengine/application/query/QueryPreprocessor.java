package com.cffex.rag.retrievalengine.application.query;

import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;

/**
 * 检索前 query 预处理器。
 */
public interface QueryPreprocessor {

    ProcessedQuery preprocess(RetrievalExecutionContext context);
}
