package com.cffex.rag.metadatacacher.infrastructure.persistence;

import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyProviderModelMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

final class DifyProviderModelConfigParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DifyProviderModelConfigParser() {
    }

    static ModelMeta toModelMeta(DifyProviderModelMapper.ProviderModelRow row, String defaultApiKey) {
        Objects.requireNonNull(row, "row must not be null");
        JsonNode config = parseConfig(row);
        return new ModelMeta(
                row.modelId(),
                resolveRuntimeModelName(row),
                MetadataConverter.toModelType(row.modelType()),
                resolveBaseUrl(row.modelType(), config),
                resolveApiKey(config, defaultApiKey),
                row.isValid()
        );
    }

    private static JsonNode parseConfig(DifyProviderModelMapper.ProviderModelRow row) {
        try {
            return OBJECT_MAPPER.readTree(defaultJson(row.encryptedConfig()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid encrypted_config for model " + row.modelId(), ex);
        }
    }

    private static String defaultJson(String encryptedConfig) {
        return encryptedConfig == null || encryptedConfig.isBlank() ? "{}" : encryptedConfig;
    }

    private static String resolveRuntimeModelName(DifyProviderModelMapper.ProviderModelRow row) {
        return firstNonBlank(row.modelName(), row.modelId());
    }

    private static String resolveApiKey(JsonNode config, String defaultApiKey) {
        return firstNonBlank(defaultApiKey, textValue(config, "api_key"));
    }


    private static String resolveBaseUrl(String modelType, JsonNode config) {
        if ("reranking".equalsIgnoreCase(modelType)) {
            return firstNonBlank(textValue(config, "base_url"), textValue(config, "endpoint_url"));
        }
        return firstNonBlank(textValue(config, "endpoint_url"), textValue(config, "base_url"));
    }

    private static String textValue(JsonNode jsonNode, String fieldName) {
        JsonNode value = jsonNode.path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
