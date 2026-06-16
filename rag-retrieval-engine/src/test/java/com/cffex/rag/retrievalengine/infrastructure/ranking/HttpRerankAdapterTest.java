package com.cffex.rag.retrievalengine.infrastructure.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HttpRerankAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void resolveRerankRequestUrl_appendsDefaultPathForHostOnlyEndpoint() {
        assertEquals(
                "https://api.jina.ai/v1/rerank",
                HttpRerankAdapter.resolveRerankRequestUrl("https://api.jina.ai")
        );
    }

    @Test
    void resolveRerankRequestUrl_appendsRerankWhenEndpointAlreadyContainsV1() {
        assertEquals(
                "https://api.jina.ai/v1/rerank",
                HttpRerankAdapter.resolveRerankRequestUrl("https://api.jina.ai/v1")
        );
    }

    @Test
    void resolveRerankRequestUrl_keepsFullRerankPathUnchanged() {
        assertEquals(
                "https://api.jina.ai/v1/rerank",
                HttpRerankAdapter.resolveRerankRequestUrl("https://api.jina.ai/v1/rerank")
        );
    }

    @Test
    void resolveRerankRequestUrl_keepsProviderSpecificRerankPathUnchanged() {
        assertEquals(
                "https://rerank.example.com/api/rerank",
                HttpRerankAdapter.resolveRerankRequestUrl("https://rerank.example.com/api/rerank/")
        );
    }

    @Test
    void resolveRerankRequestUrl_appendsRerankForCustomGatewayBasePath() {
        assertEquals(
                "http://172.31.71.71/jina_like_rerank/rerank",
                HttpRerankAdapter.resolveRerankRequestUrl("http://172.31.71.71/jina_like_rerank/")
        );
    }

    @Test
    void buildRerankRequest_usesJinaCompatibleBody() throws Exception {
        HttpRerankAdapter.RerankRequest request = HttpRerankAdapter.buildRerankRequest(
                "jina-reranker-v2-base-multilingual",
                "你的查询文本",
                List.of("文档1", "文档2", "文档3"),
                5
        );

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "model": "jina-reranker-v2-base-multilingual",
                          "query": "你的查询文本",
                          "documents": ["文档1", "文档2", "文档3"],
                          "top_n": 3,
                          "return_documents": true,
                          "score_threshold": 0.0
                        }
                        """),
                OBJECT_MAPPER.valueToTree(request)
        );
    }

    @Test
    void buildRerankRequest_includesScoreThresholdWhenEnabled() throws Exception {
        HttpRerankAdapter.RerankRequest request = HttpRerankAdapter.buildRerankRequest(
                "jina-reranker-v2-base-multilingual",
                "你的查询文本",
                List.of("文档1", "文档2"),
                1,
                0.35d
        );

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "model": "jina-reranker-v2-base-multilingual",
                          "query": "你的查询文本",
                          "documents": ["文档1", "文档2"],
                          "top_n": 1,
                          "return_documents": true,
                          "score_threshold": 0.35
                        }
                        """),
                OBJECT_MAPPER.valueToTree(request)
        );
    }

    @Test
    void dedupeLikeDify_keepsFirstCandidateForSameDocId() {
        RetrievalCandidate first = candidate("chunk-1", "doc-1", "kb-1", "first", Map.of("doc_id", "seg-1"));
        RetrievalCandidate duplicate = candidate("chunk-2", "doc-1", "kb-1", "duplicate", Map.of("doc_id", "seg-1"));
        RetrievalCandidate other = candidate("chunk-3", "doc-2", "kb-1", "other", Map.of("doc_id", "seg-2"));

        List<RetrievalCandidate> deduped = HttpRerankAdapter.dedupeLikeDify(List.of(first, duplicate, other));

        assertEquals(List.of(first, other), deduped);
    }

    @Test
    void dedupeLikeDify_readsNestedMetadataDocIdFromMilvusEntity() {
        RetrievalCandidate first = candidate(
                "chunk-1",
                "doc-1",
                "kb-1",
                "first",
                Map.of("metadata", Map.of("doc_id", "seg-1"))
        );
        RetrievalCandidate duplicate = candidate(
                "chunk-2",
                "doc-1",
                "kb-1",
                "duplicate",
                Map.of("metadata", Map.of("doc_id", "seg-1"))
        );
        RetrievalCandidate other = candidate(
                "chunk-3",
                "doc-2",
                "kb-1",
                "other",
                Map.of("metadata", Map.of("doc_id", "seg-2"))
        );

        List<RetrievalCandidate> deduped = HttpRerankAdapter.dedupeLikeDify(List.of(first, duplicate, other));

        assertEquals(List.of(first, other), deduped);
    }

    @Test
    void allowsScore_filtersScoresBelowEnabledThreshold() {
        assertEquals(false, HttpRerankAdapter.allowsScore(0.34d, 0.35d));
        assertEquals(true, HttpRerankAdapter.allowsScore(0.35d, 0.35d));
        assertEquals(true, HttpRerankAdapter.allowsScore(0.34d, 0.0d));
        assertEquals(true, HttpRerankAdapter.allowsScore(0.34d, null));
    }

    @Test
    void rerankResponse_deserializesJinaStyleResultsAndUsage() throws Exception {
        HttpRerankAdapter.RerankResponse response = OBJECT_MAPPER.readValue("""
                {
                  "results": [
                    {
                      "document": {
                        "text": "Organic skincare for sensitive skin with aloe vera and chamomile..."
                      },
                      "index": 0,
                      "relevance_score": 0.8783142566680908
                    },
                    {
                      "document": {
                        "text": "Bio-Hautpflege fuer empfindliche Haut mit Aloe Vera und Kamille..."
                      },
                      "index": 2,
                      "relevance_score": 0.7624675869941711
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 815,
                    "completion_tokens": 0,
                    "total_tokens": 815
                  }
                }
                """, HttpRerankAdapter.RerankResponse.class);

        assertEquals(2, response.results().size());
        assertEquals(0, response.results().get(0).index());
        assertEquals(
                "Organic skincare for sensitive skin with aloe vera and chamomile...",
                response.results().get(0).document().text()
        );
        assertEquals(815, response.usage().promptTokens());
        assertEquals(0, response.usage().completionTokens());
        assertEquals(815, response.usage().totalTokens());
    }

    private static RetrievalCandidate candidate(
            String chunkId,
            String documentId,
            String knowledgeBaseId,
            String content,
            Map<String, Object> metadata
    ) {
        return new RetrievalCandidate(chunkId, documentId, knowledgeBaseId, content, 0.0d, null, metadata);
    }
}
