package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;
import com.cffex.rag.retrievalengine.application.query.DefaultQueryPreprocessor;
import com.cffex.rag.retrievalengine.application.query.ProcessedQuery;
import com.cffex.rag.retrievalengine.application.retrieval.MultiKnowledgeBaseRecallService;

/**
 * 多知识库组合检索器。
 *
 * <p>这是保留给 Spring AI Advisor/DocumentRetriever 场景使用的适配器。
 * 它会从 Spring AI Query context 中恢复内部执行命令，再委托给内部召回服务。
 */
@Component
public class MultiKnowledgeBaseDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiKnowledgeBaseDocumentRetriever.class);

    private final MultiKnowledgeBaseRecallService multiKnowledgeBaseRecallService;

    public MultiKnowledgeBaseDocumentRetriever(
            MultiKnowledgeBaseRecallService multiKnowledgeBaseRecallService
    ) {
        this.multiKnowledgeBaseRecallService = Objects.requireNonNull(multiKnowledgeBaseRecallService);
    }

    @Override
    public List<Document> retrieve(Query query) {
        // 这是给 RetrievalAugmentationAdvisor 用的桥接入口：
        // Spring AI Query -> 内部执行命令 -> 内部候选 -> Spring AI Document。
        ExecuteRetrievalCommand command = DefaultQueryPreprocessor.retrievalCommand(query);
        List<Document> documents = multiKnowledgeBaseRecallService.recall(
                        new ProcessedQuery(query.text()),
                        new RetrievalExecutionContext(command)
                ).stream()
                .map(RetrievalDocumentMapper::toDocument)
                .toList();

        log.debug("Spring AI 多知识库检索完成 | kbCount={}, documentCount={}",
                command.recallCommands().size(),
                documents.size());
        return documents;
    }
}
