package com.cffex.rag.metadatacacher.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyProviderModelMapper;
import org.junit.jupiter.api.Test;

class DifyProviderModelConfigParserTest {

    @Test
    void toModelMeta_prefersDefaultApiKeyWhenConfigured() {
        DifyProviderModelMapper.ProviderModelRow row = new DifyProviderModelMapper.ProviderModelRow(
                "uuid-model-id",
                "tenant-1",
                "openai",
                "bge_m3",
                "text_embedding",
                "{\"api_key\":\"encrypted-value\",\"endpoint_url\":\"http://embedding\"}",
                true
        );

        var modelMeta = DifyProviderModelConfigParser.toModelMeta(row, "plain-default-token");

        assertEquals("uuid-model-id", modelMeta.modelId());
        assertEquals("bge_m3", modelMeta.modelName());
        assertEquals(ModelType.EMBEDDING, modelMeta.modelType());
        assertEquals("http://embedding", modelMeta.baseUrl());
        assertEquals("plain-default-token", modelMeta.apiKey());
    }

    @Test
    void toModelMeta_fallsBackToEncryptedConfigApiKeyWhenDefaultMissing() {
        DifyProviderModelMapper.ProviderModelRow row = new DifyProviderModelMapper.ProviderModelRow(
                "uuid-model-id",
                "tenant-1",
                "jina",
                "jina-reranker",
                "reranking",
                "{\"api_key\":\"encrypted-value\",\"base_url\":\"http://rerank\"}",
                true
        );

        var modelMeta = DifyProviderModelConfigParser.toModelMeta(row, null);

        assertEquals("jina-reranker", modelMeta.modelName());
        assertEquals(ModelType.RERANK, modelMeta.modelType());
        assertEquals("http://rerank", modelMeta.baseUrl());
        assertEquals("encrypted-value", modelMeta.apiKey());
    }

    @Test
    void toModelMeta_usesConfiguredDefaultApiKeyForLlmModels() {
        DifyProviderModelMapper.ProviderModelRow row = new DifyProviderModelMapper.ProviderModelRow(
                "llm-uuid",
                "tenant-1",
                "openai",
                "gpt-4o-mini",
                "llm",
                "{\"api_key\":\"encrypted-value\",\"endpoint_url\":\"https://llm.example.com/v1\"}",
                true
        );

        var modelMeta = DifyProviderModelConfigParser.toModelMeta(row, "plain-default-token");

        assertEquals("gpt-4o-mini", modelMeta.modelName());
        assertEquals(ModelType.LLM, modelMeta.modelType());
        assertEquals("https://llm.example.com/v1", modelMeta.baseUrl());
        assertEquals("plain-default-token", modelMeta.apiKey());
    }
}
