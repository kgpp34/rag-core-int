package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cffex.rag.retrievalengine.config.MilvusProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

class MilvusSearchSupportTest {

    private MilvusSearchSupport searchSupport;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MilvusProperties properties = new MilvusProperties(
                "http://127.0.0.1:19530",
                "root",
                "Milvus",
                "default",
                "page_content",
                "metadata",
                "vector"
        );
        objectMapper = new ObjectMapper();
        searchSupport = new MilvusSearchSupport(properties, objectMapper);
    }

    @Test
    void toDenseCandidates_convertsGsonJsonObjectInMetadataToJacksonFriendlyTypes() {
        JsonObject metadataJson = new JsonObject();
        metadataJson.addProperty("document_id", "doc-1");
        metadataJson.addProperty("source", "policy-manual");
        metadataJson.add("tags", new JsonArray());
        metadataJson.getAsJsonArray("tags").add(new JsonPrimitive("finance"));
        metadataJson.getAsJsonArray("tags").add(new JsonPrimitive("compliance"));

        JsonObject innerNested = new JsonObject();
        innerNested.addProperty("key", "value");
        metadataJson.add("nested", innerNested);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("page_content", "some content");
        entity.put("metadata", metadataJson);

        Map<String, Object> rawSearchResult = new LinkedHashMap<>(entity);

        List<RetrievalCandidate> candidates = searchSupport.toDenseCandidates(
                buildMockSearchResp(rawSearchResult, 0.92f),
                "kb-1"
        );

        assertThat(candidates).hasSize(1);
        RetrievalCandidate candidate = candidates.getFirst();
        assertThat(candidate.documentId()).isEqualTo("doc-1");

        Object metadataValue = candidate.metadata().get("metadata");
        assertThat(metadataValue).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) metadataValue;
        assertThat(metadataMap.get("source")).isEqualTo("policy-manual");
        assertThat(metadataMap.get("tags")).isInstanceOf(List.class);
        assertThat(metadataMap.get("nested")).isInstanceOf(Map.class);

        assertThatCodeNoException(() -> objectMapper.writeValueAsString(candidate.metadata()));
    }

    @Test
    void toDenseCandidates_handlesPureJavaTypesWithoutModification() {
        Map<String, Object> metadataMap = new LinkedHashMap<>();
        metadataMap.put("document_id", "doc-2");
        metadataMap.put("page_count", 42);
        metadataMap.put("is_valid", true);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("page_content", "plain content");
        entity.put("metadata", metadataMap);

        List<RetrievalCandidate> candidates = searchSupport.toDenseCandidates(
                buildMockSearchResp(entity, 0.85f),
                "kb-2"
        );

        assertThat(candidates).hasSize(1);
        Object metadataValue = candidates.getFirst().metadata().get("metadata");
        assertThat(metadataValue).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) metadataValue;
        assertThat(result.get("page_count")).isEqualTo(42);
        assertThat(result.get("is_valid")).isEqualTo(true);

        assertThatCodeNoException(() -> objectMapper.writeValueAsString(candidates.getFirst().metadata()));
    }

    @Test
    void toDenseCandidates_serializesFullCandidateMetadataAsValidJson() throws Exception {
        JsonObject gsonMeta = new JsonObject();
        gsonMeta.addProperty("document_id", "doc-3");
        gsonMeta.addProperty("score_info", "high");

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("page_content", "test content");
        entity.put("metadata", gsonMeta);

        List<RetrievalCandidate> candidates = searchSupport.toDenseCandidates(
                buildMockSearchResp(entity, 0.95f),
                "kb-3"
        );

        String json = objectMapper.writeValueAsString(candidates.getFirst().metadata());
        assertThat(json).contains("document_id");
        assertThat(json).contains("doc-3");
    }

    private static io.milvus.v2.service.vector.response.SearchResp buildMockSearchResp(
            Map<String, Object> entity,
            float score
    ) {
        return io.milvus.v2.service.vector.response.SearchResp.builder()
                .searchResults(List.of(List.of(
                        io.milvus.v2.service.vector.response.SearchResp.SearchResult.builder()
                                .id("chunk-1")
                                .score(score)
                                .entity(entity)
                                .build()
                )))
                .build();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertThatCodeNoException(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception ex) {
            throw new AssertionError("Expected no exception, but got: " + ex.getMessage(), ex);
        }
    }
}
