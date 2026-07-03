package com.cffex.rag.retrievalengine.infrastructure.embedding;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.cffex.rag.retrievalengine.config.RetrievalHttpClientProperties;
import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.QueryEmbeddingPort;
import com.cffex.rag.retrievalengine.infrastructure.http.PoolingRestClientFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 基于 OpenAI 兼容 embedding 模型的查询向量化适配器。
 */
@Component
public class OpenAiCompatibleQueryEmbeddingAdapter implements QueryEmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleQueryEmbeddingAdapter.class);

    private static final String DIFY_QUERY_INPUT_TYPE = "query";

    private final RestClient restClient;

    @Autowired
    public OpenAiCompatibleQueryEmbeddingAdapter(
            RestClient.Builder restClientBuilder,
            RetrievalHttpClientProperties httpClientProperties
    ) {
        this.restClient = PoolingRestClientFactory.create(
                Objects.requireNonNull(restClientBuilder),
                Objects.requireNonNull(httpClientProperties)
        );
    }

    OpenAiCompatibleQueryEmbeddingAdapter(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public float[] embed(String queryText, EmbeddingModelPolicy embeddingModel) {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        log.debug("embedding | model={}, endpoint={}", embeddingModel.model(), embeddingModel.endpoint());

        EmbeddingResponse response = restClient
                .post()
                .uri(resolveEmbeddingsRequestUrl(embeddingModel.endpoint()))
                .header("Authorization", "Bearer " + embeddingModel.authToken())
                .body(new EmbeddingRequest(embeddingModel.model(), queryText, DIFY_QUERY_INPUT_TYPE))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("embedding response data is empty");
        }
        return normalize(toFloatArray(response.data().getFirst().embedding()));
    }

    static String resolveEmbeddingsRequestUrl(String endpoint) {
        String normalized = trimTrailingSlash(endpoint);
        URI uri = URI.create(normalized);
        String path = uri.getPath();
        String resolvedPath;
        if (path == null || path.isBlank() || "/".equals(path)) {
            resolvedPath = "/v1/embeddings";
        } else if (path.endsWith("/v1/embeddings") || path.endsWith("/embeddings")) {
            resolvedPath = path;
        } else if (path.endsWith("/v1")) {
            resolvedPath = path + "/embeddings";
        } else {
            resolvedPath = path + "/embeddings";
        }
        return rebuildUri(uri, resolvedPath);
    }

    static float[] normalize(float[] vector) {
        double normSquared = 0.0d;
        for (float value : vector) {
            normSquared += value * value;
        }
        double norm = Math.sqrt(normSquared);
        if (norm == 0.0d || Double.isNaN(norm) || Double.isInfinite(norm)) {
            throw new IllegalStateException("embedding norm is invalid");
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    private static float[] toFloatArray(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("embedding vector is empty");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null || value.isNaN() || value.isInfinite()) {
                throw new IllegalStateException("embedding vector contains invalid value");
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }

    private static String trimTrailingSlash(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String rebuildUri(URI uri, String path) {
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    path,
                    uri.getQuery(),
                    uri.getFragment()
            ).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid embedding endpoint: " + uri, ex);
        }
    }

    record EmbeddingRequest(
            String model,
            String input,
            @JsonProperty("input_type") String inputType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(List<Double> embedding) {}
}
