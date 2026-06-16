package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.config.ChatProperties;
import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

/**
 * 可选的 RetrievalAugmentationAdvisor 提供器。
 *
 * <p>当前 LlmRequest 尚未承载 RetrievalPlan，因此默认关闭。开启后若请求上下文中
 * 没有检索执行信息，本 provider 会优雅降级为不注入任何文档。
 */
@Component
public class RetrievalAugmentationChatAdvisorProvider implements ChatAdvisorContributor {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAugmentationChatAdvisorProvider.class);

    private final ChatProperties properties;
    private final List<QueryTransformer> queryTransformers;
    private final DocumentRetriever safeDocumentRetriever;

    public RetrievalAugmentationChatAdvisorProvider(
            ChatProperties properties,
            List<QueryTransformer> queryTransformers,
            MultiKnowledgeBaseDocumentRetriever multiKnowledgeBaseDocumentRetriever
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.queryTransformers = List.copyOf(queryTransformers);
        this.safeDocumentRetriever = new SafeRetrievalDocumentRetriever(multiKnowledgeBaseDocumentRetriever);
    }

    @Override
    public List<Advisor> advisors(ChatCompletionRequest request) {
        if (!properties.retrievalAugmentationAdvisorEnabled()) {
            return List.of();
        }
        // 只要显式开启，就把 QueryTransformer + DocumentRetriever 一起挂到 RetrievalAugmentationAdvisor。
        // 真正是否能检索到文档，取决于当前请求上下文里有没有检索执行信息。
        return List.of(RetrievalAugmentationAdvisor.builder()
                .queryTransformers(queryTransformers)
                .documentRetriever(safeDocumentRetriever)
                .build());
    }

    private static final class SafeRetrievalDocumentRetriever implements DocumentRetriever {

        private final MultiKnowledgeBaseDocumentRetriever delegate;

        private SafeRetrievalDocumentRetriever(MultiKnowledgeBaseDocumentRetriever delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Document> retrieve(Query query) {
            try {
                return delegate.retrieve(query);
            } catch (IllegalStateException ex) {
                // 聊天请求不一定总带检索上下文；这里失败时直接吞掉并降级为空文档，避免增强能力反噬主流程。
                log.debug("当前 ChatClient 请求未携带 RetrievalPlan，上下文增强跳过，原因={}", ex.getMessage());
                return List.of();
            }
        }
    }
}
