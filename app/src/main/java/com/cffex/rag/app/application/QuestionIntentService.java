package com.cffex.rag.app.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.domain.llm.LlmMessage;
import com.cffex.rag.common.domain.llm.LlmMessageRole;
import com.cffex.rag.common.domain.llm.LlmRequest;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.service.LlmService;
import com.cffex.rag.common.service.MetadataQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

/** Classifies before either answer pipeline; classification never enters conversation memory. */
final class QuestionIntentService {

    enum Intent { CAPABILITY_INTRO, KNOWLEDGE_QUERY }

    private static final Logger log = LoggerFactory.getLogger(QuestionIntentService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final LlmService llmService;
    private final MetadataQueryService metadataQueryService;
    private final AppProperties.Intent properties;

    QuestionIntentService(LlmService llmService, MetadataQueryService metadataQueryService,
            AppProperties properties) {
        this.llmService = llmService;
        this.metadataQueryService = metadataQueryService;
        this.properties = properties.getRag().getIntent();
    }

    Intent classify(String query, Supplier<ModelEndpointSpec> endpoint) {
        if (!properties.isEnabled()) {
            return Intent.KNOWLEDGE_QUERY;
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        FutureTask<Intent> task = new FutureTask<>(() -> {
            try {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                LlmRequest request = new LlmRequest(endpoint.get(), List.of(
                        new LlmMessage(LlmMessageRole.SYSTEM, properties.getPrompt()),
                        new LlmMessage(LlmMessageRole.USER, query)
                ), 0.0d, 64, null, null);
                JsonNode result = JSON.readTree(llmService.generate(request).content());
                if (result == null || !result.isObject() || result.size() != 1
                        || !result.path("intent").isTextual()) {
                    throw new IllegalArgumentException("Invalid intent response");
                }
                return Intent.valueOf(result.get("intent").textValue());
            } finally {
                MDC.clear();
            }
        });
        Thread.ofVirtual().name("question-intent").start(task);
        try {
            return task.get(properties.getTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("意图分类被中断，继续原问答流程");
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("意图分类失败或超时，继续原问答流程，原因={}", ex.getClass().getSimpleName());
        } finally {
            task.cancel(true);
        }
        return Intent.KNOWLEDGE_QUERY;
    }

    String introduction(List<String> docIds) {
        String knowledgeBases;
        try {
            List<String> names = metadataQueryService
                    .listKnowledgeBases(new KnowledgeBaseQueryCondition(docIds)).stream()
                    .filter(KnowledgeBaseMeta::enabled)
                    // Metadata falls back to its internal ID when the display name is absent.
                    .filter(kb -> kb.name() != null && !kb.name().isBlank()
                            && !kb.name().equals(kb.knowledgeBaseId()))
                    .map(kb -> kb.name().replaceAll("[\\r\\n]+", " ").strip())
                    .distinct().sorted().toList();
            if (names.isEmpty()) {
                knowledgeBases = "当前暂未获取到可展示的知识库名称，你可以围绕已选择的知识库或文档提问。";
            } else {
                String scope = docIds.isEmpty() ? "当前已接入的知识库包括：" : "当前所选文档对应的知识库包括：";
                knowledgeBases = scope + "\n\n" + String.join("\n", names.stream()
                        .limit(properties.getMaxKnowledgeBaseNames()).map(name -> "- " + name).toList());
                if (names.size() > properties.getMaxKnowledgeBaseNames()) {
                    knowledgeBases += "\n\n以上展示部分知识库，共有 " + names.size() + " 个不同知识库名称。";
                }
            }
        } catch (RuntimeException ex) {
            log.warn("读取知识库名称失败，返回基础能力介绍，原因={}", ex.getClass().getSimpleName());
            knowledgeBases = "当前暂时无法获取知识库列表，你可以围绕已选择的知识库或文档提问。";
        }
        return properties.getIntroduction().replace("{knowledgeBases}", knowledgeBases).strip();
    }
}
