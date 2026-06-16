package com.cffex.rag.retrievalengine.infrastructure.embedding;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;

/**
 * 基于 OpenAI 兼容协议构建并缓存 EmbeddingModel。
 *
 * <p>同一个 endpoint/model 组合会被复用，避免每次向量检索都重复创建底层模型对象。
 */
@Component
public class OpenAiCompatibleEmbeddingModelFactory {

    private final ConcurrentHashMap<EmbeddingModelPolicy, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    public EmbeddingModel getEmbeddingModel(EmbeddingModelPolicy policy) {
        return modelCache.computeIfAbsent(policy, this::buildModel);
    }

    private EmbeddingModel buildModel(EmbeddingModelPolicy policy) {
        // 这里假定上游传入的是 OpenAI 兼容端点，因此直接用 Spring AI 的 OpenAiApi/OpenAiEmbeddingModel 适配。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(policy.endpoint())
                .apiKey(policy.authToken())
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(policy.model())
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }
}
