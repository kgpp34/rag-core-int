package com.cffex.rag.retrievalengine.application.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;

/**
 * 默认 query 预处理器。
 *
 * <p>边界收缩阶段继续复用 Spring AI 的 {@link QueryTransformer} 链，但只在预处理器内部使用
 * Spring AI Query，对外统一返回内部 {@link ProcessedQuery}。
 */
@Component
public class DefaultQueryPreprocessor implements QueryPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryPreprocessor.class);

    public static final String RETRIEVAL_COMMAND_CONTEXT_KEY = "retrievalCommand";

    private final List<QueryTransformer> queryTransformers;

    public DefaultQueryPreprocessor(List<QueryTransformer> queryTransformers) {
        this.queryTransformers = List.copyOf(queryTransformers);
    }

    @Override
    public ProcessedQuery preprocess(RetrievalExecutionContext context) {
        ExecuteRetrievalCommand command = context.command();
        String originalText = command.queryText();

        Query query = new Query(originalText, List.of(), Map.of(RETRIEVAL_COMMAND_CONTEXT_KEY, command));
        for (QueryTransformer queryTransformer : queryTransformers) {
            String beforeTransform = query.text();
            query = attachCommand(queryTransformer.transform(query), command);
            if (!beforeTransform.equals(query.text())) {
                log.debug("query经过[{}]变换 | beforeLength={}, afterLength={}",
                        queryTransformer.getClass().getSimpleName(),
                        beforeTransform.length(),
                        query.text().length());
            }
        }

        boolean rewritten = !originalText.equals(query.text());
        if (rewritten) {
            log.debug("query改写 | originalLength={}, processedLength={}", originalText.length(), query.text().length());
        } else {
            log.debug("query未改写");
        }

        return new ProcessedQuery(query.text());
    }

    public static ExecuteRetrievalCommand retrievalCommand(Query query) {
        Objects.requireNonNull(query, "query must not be null");
        Object command = query.context().get(RETRIEVAL_COMMAND_CONTEXT_KEY);
        if (command instanceof ExecuteRetrievalCommand retrievalCommand) {
            return retrievalCommand;
        }
        throw new IllegalStateException("Spring AI Query context missing retrievalCommand");
    }

    private static Query attachCommand(Query query, ExecuteRetrievalCommand command) {
        Map<String, Object> context = new LinkedHashMap<>(query.context());
        context.put(RETRIEVAL_COMMAND_CONTEXT_KEY, command);
        return new Query(query.text(), query.history(), Map.copyOf(context));
    }
}
