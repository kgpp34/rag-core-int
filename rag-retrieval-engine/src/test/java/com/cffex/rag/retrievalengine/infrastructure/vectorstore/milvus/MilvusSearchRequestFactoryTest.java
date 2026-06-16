package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cffex.rag.retrievalengine.config.MilvusProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;

class MilvusSearchRequestFactoryTest {

    private MilvusSearchRequestFactory factory;

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
        MilvusSearchSupport searchSupport = new MilvusSearchSupport(properties, new ObjectMapper());
        factory = new MilvusSearchRequestFactory(properties, searchSupport);
    }

    @Test
    void buildDenseSearchRequest_appliesSharedBaseFieldsAndDenseSettings() {
        SearchReq request = factory.buildDenseSearchRequest(
                "collection-1",
                List.of("doc-1", "doc-2"),
                4,
                new float[]{0.1f, 0.2f}
        );

        assertThat(request.getCollectionName()).isEqualTo("collection-1");
        assertThat(request.getTopK()).isEqualTo(4);
        assertThat(request.getAnnsField()).isEqualTo("vector");
        assertThat(request.getMetricType()).isEqualTo(IndexParam.MetricType.IP);
        assertThat(request.getSearchParams()).containsEntry("ef", 64);
        assertThat(request.getFilter()).isEqualTo("metadata[\"document_id\"] in [\"doc-1\", \"doc-2\"]");
        assertThat(request.getOutputFields()).containsExactly("page_content", "metadata");
        assertThat(request.getData()).hasSize(1);
    }

    @Test
    void buildSparseSearchRequest_appliesSharedBaseFieldsAndSparseSettings() {
        SearchReq request = factory.buildSparseSearchRequest(
                "collection-2",
                List.of(),
                6,
                "search text"
        );

        assertThat(request.getCollectionName()).isEqualTo("collection-2");
        assertThat(request.getTopK()).isEqualTo(6);
        assertThat(request.getAnnsField()).isEqualTo("sparse_vector");
        assertThat(request.getMetricType()).isEqualTo(IndexParam.MetricType.BM25);
        assertThat(request.getFilter()).isNull();
        assertThat(request.getOutputFields()).containsExactly("page_content", "metadata");
        assertThat(request.getData()).hasSize(1);
    }
}
