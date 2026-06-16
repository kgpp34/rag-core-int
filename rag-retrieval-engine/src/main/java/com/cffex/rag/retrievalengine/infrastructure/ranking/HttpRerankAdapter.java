package com.cffex.rag.retrievalengine.infrastructure.ranking;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.RerankPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 基于 HTTP 的 rerank 适配器。
 *
 * <p>默认请求格式为 {@code POST /v1/rerank}，请求体为
 * {@code {model, query, documents, top_n, return_documents}}，
 * 响应体兼容 Jina 风格的
 * {@code {results: [{document: {text}, index, relevance_score}], usage}}。
 *
 * <p>连接参数已由规划阶段填入领域 {@link RerankModelPolicy}，
 * 本适配器不再依赖 MetadataQueryService。
 */
@Component
public class HttpRerankAdapter implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRerankAdapter.class);

    private final RestClient.Builder restClientBuilder;

    public HttpRerankAdapter(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public Map<String, Double> rerank(String query,
                                      RerankModelPolicy policy,
                                      List<RetrievalCandidate> candidates,
                                      int topN,
                                      Double scoreThreshold) {
        if (candidates.isEmpty()) {
            return Map.of();
        }

        List<RetrievalCandidate> uniqueCandidates = dedupeLikeDify(candidates);
        List<String> documents = uniqueCandidates.stream()
                .map(RetrievalCandidate::content)
                .toList();

        // 这里把内部候选列表压平成外部 rerank API 所需的纯文本 documents，
        // 再依赖返回的 index 反查回原始 candidateKey。
        String requestUrl = resolveRerankRequestUrl(policy.endpoint());
        RerankRequest requestBody = buildRerankRequest(policy.model(), query, documents, topN, scoreThreshold);
        log.debug(
                "调用 rerank 接口 | url={}, model={}, candidateCount={}, uniqueCount={}, topN={}, scoreThreshold={}",
                requestUrl,
                policy.model(),
                candidates.size(),
                uniqueCandidates.size(),
                requestBody.topN(),
                scoreThreshold
        );

        RerankResponse response = restClientBuilder
                .build()
                .post()
                .uri(requestUrl)
                .header("Authorization", "Bearer " + policy.authToken())
                .body(requestBody)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            return Map.of();
        }

        if (response.usage() != null) {
            log.debug(
                    "rerank 响应 | results={}, promptTokens={}, completionTokens={}, totalTokens={}",
                    response.results().size(),
                    response.usage().promptTokens(),
                    response.usage().completionTokens(),
                    response.usage().totalTokens()
            );
        } else {
            log.debug("rerank 响应 | results={}", response.results().size());
        }
        logRerankResults(response.results(), uniqueCandidates);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (RerankResult result : response.results()) {
            if (result.index() < 0 || result.index() >= uniqueCandidates.size()) {
                log.warn("忽略越界 rerank 结果，index={}，候选数={}", result.index(), uniqueCandidates.size());
                continue;
            }
            if (!allowsScore(result.relevanceScore(), scoreThreshold)) {
                continue;
            }
            // 外部模型只知道文档顺序，不知道内部 chunkId/documentId，因此这里用 index 回写到 candidateKey。
            scores.put(uniqueCandidates.get(result.index()).candidateKey(), result.relevanceScore());
        }
        return Map.copyOf(scores);
    }

    private void logRerankResults(List<RerankResult> results, List<RetrievalCandidate> uniqueCandidates) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("rerank 返回明细 | top={}",
                results.stream()
                        .limit(100)
                        .map(result -> rerankResultSummary(result, uniqueCandidates))
                        .toList());
    }

    private static String rerankResultSummary(RerankResult result, List<RetrievalCandidate> uniqueCandidates) {
        if (result.index() < 0 || result.index() >= uniqueCandidates.size()) {
            return "index=" + result.index() + ", score=" + result.relevanceScore() + ", outOfRange=true";
        }
        RetrievalCandidate candidate = uniqueCandidates.get(result.index());
        return "index=" + result.index()
                + ", score=" + result.relevanceScore()
                + ", kbId=" + candidate.knowledgeBaseId()
                + ", chunkId=" + candidate.chunkId()
                + ", docId=" + difyDocId(candidate)
                + ", text=" + abbreviate(candidate.content(), 80);
    }

    static String resolveRerankRequestUrl(String endpoint) {
        String normalized = trimTrailingSlash(endpoint);
        URI uri = URI.create(normalized);
        String path = uri.getPath();
        String resolvedPath;
        if (path == null || path.isBlank() || "/".equals(path)) {
            resolvedPath = "/v1/rerank";
        } else if (path.endsWith("/v1/rerank") || path.endsWith("/rerank")) {
            resolvedPath = path;
        } else if (path.endsWith("/v1")) {
            resolvedPath = path + "/rerank";
        } else {
            // 已显式配置自定义 base path 时，继续拼接 rerank 子路径。
            resolvedPath = path + "/rerank";
        }
        return rebuildUri(uri, resolvedPath);
    }

    static RerankRequest buildRerankRequest(String model, String query, List<String> documents, int topN) {
        return buildRerankRequest(model, query, documents, topN, 0.0d);
    }

    static RerankRequest buildRerankRequest(
            String model,
            String query,
            List<String> documents,
            int topN,
            Double scoreThreshold
    ) {
        return new RerankRequest(
                model,
                query,
                documents,
                resolveTopN(topN, documents.size()),
                true,
                scoreThreshold
        );
    }

    static List<RetrievalCandidate> dedupeLikeDify(List<RetrievalCandidate> candidates) {
        Map<String, RetrievalCandidate> uniqueCandidates = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            uniqueCandidates.putIfAbsent(difyDocId(candidate), candidate);
        }
        return List.copyOf(uniqueCandidates.values());
    }

    static boolean allowsScore(double score, Double scoreThreshold) {
        return scoreThreshold == null || scoreThreshold <= 0.0d || score >= scoreThreshold;
    }

    private static String difyDocId(RetrievalCandidate candidate) {
        Object docId = candidate.metadata().get("doc_id");
        if (hasText(docId)) {
            return docId.toString();
        }
        Object metadata = candidate.metadata().get("metadata");
        if (metadata instanceof Map<?, ?> nested) {
            Object nestedDocId = nested.get("doc_id");
            if (hasText(nestedDocId)) {
                return nestedDocId.toString();
            }
        }
        return candidate.candidateKey();
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static String abbreviate(String text, int maxLength) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private static String trimTrailingSlash(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int resolveTopN(int requestedTopN, int candidateCount) {
        if (candidateCount <= 0) {
            return 0;
        }
        if (requestedTopN <= 0) {
            return candidateCount;
        }
        return Math.min(requestedTopN, candidateCount);
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
            throw new IllegalArgumentException("Invalid rerank endpoint: " + uri, ex);
        }
    }

    // ----------------------------- 内部 DTO --------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RerankRequest(String model,
                         String query,
                         List<String> documents,
                         @JsonProperty("top_n") int topN,
                         @JsonProperty("return_documents") boolean returnDocuments,
                         @JsonProperty("score_threshold") Double scoreThreshold) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<RerankResult> results, RerankUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResult(RerankDocument document,
                        int index,
                        @JsonProperty("relevance_score") double relevanceScore) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankDocument(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankUsage(@JsonProperty("prompt_tokens") Integer promptTokens,
                       @JsonProperty("completion_tokens") Integer completionTokens,
                       @JsonProperty("total_tokens") Integer totalTokens) {}
}
