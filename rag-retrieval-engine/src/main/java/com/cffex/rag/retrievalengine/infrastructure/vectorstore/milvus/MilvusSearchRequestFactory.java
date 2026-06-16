package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.config.MilvusProperties;

import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;

/**
 * Milvus 查询请求工厂：统一构造 dense/sparse 两条路由的 SearchReq。
 */
@Component
public class MilvusSearchRequestFactory {

    private static final String SPARSE_VECTOR_FIELD = "sparse_vector";
    private static final int DIFY_HNSW_EF = 64;

    private final MilvusProperties properties;
    private final MilvusSearchSupport searchSupport;

    public MilvusSearchRequestFactory(MilvusProperties properties, MilvusSearchSupport searchSupport) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.searchSupport = Objects.requireNonNull(searchSupport, "searchSupport must not be null");
    }

    public SearchReq buildDenseSearchRequest(
            String collectionName,
            List<String> docIds,
            int topK,
            float[] queryVector
    ) {
        SearchReq.SearchReqBuilder<?, ?> builder = baseBuilder(collectionName, docIds, topK)
                .annsField(properties.embeddingFieldName())
                .metricType(IndexParam.MetricType.IP)
                .searchParams(Map.of("ef", DIFY_HNSW_EF))
                .data(List.of(new FloatVec(queryVector)));
        return builder.build();
    }

    public SearchReq buildSparseSearchRequest(
            String collectionName,
            List<String> docIds,
            int topK,
            String queryText
    ) {
        SearchReq.SearchReqBuilder<?, ?> builder = baseBuilder(collectionName, docIds, topK)
                .annsField(SPARSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .data(List.of(new EmbeddedText(queryText)));
        return builder.build();
    }

    public Map<String, Object> describeDenseSearchRequest(
            String collectionName,
            List<String> docIds,
            int topK,
            float[] queryVector
    ) {
        Map<String, Object> description = baseDescription(
                collectionName,
                docIds,
                topK,
                properties.embeddingFieldName(),
                "IP"
        );
        description.putAll(denseDescription(queryVector));
        return description;
    }

    public Map<String, Object> describeSparseSearchRequest(
            String collectionName,
            List<String> docIds,
            int topK,
            String queryText
    ) {
        Map<String, Object> description = baseDescription(collectionName, docIds, topK, SPARSE_VECTOR_FIELD, "BM25");
        description.put("queryText", queryText);
        description.put("queryLength", queryText.length());
        return description;
    }

    private SearchReq.SearchReqBuilder<?, ?> baseBuilder(
            String collectionName,
            List<String> docIds,
            int topK
    ) {
        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                .collectionName(collectionName)
                .topK(topK)
                .outputFields(searchSupport.outputFields());
        if (!docIds.isEmpty()) {
            builder.filter(searchSupport.buildDocIdFilter(docIds));
        }
        return builder;
    }

    private Map<String, Object> baseDescription(
            String collectionName,
            List<String> docIds,
            int topK,
            String annsField,
            String metricType
    ) {
        Map<String, Object> description = new java.util.LinkedHashMap<>();
        description.put("collectionName", collectionName);
        description.put("topK", topK);
        description.put("docFilterCount", docIds.size());
        description.put("docIds", docIds);
        description.put("filter", docIds.isEmpty() ? null : searchSupport.buildDocIdFilter(docIds));
        description.put("outputFields", searchSupport.outputFields());
        description.put("annsField", annsField);
        description.put("metricType", metricType);
        return description;
    }

    private Map<String, Object> denseDescription(float[] queryVector) {
        Map<String, Object> description = new java.util.LinkedHashMap<>();
        description.put("searchParams", Map.of("ef", DIFY_HNSW_EF));
        description.put("queryVectorDim", queryVector.length);
        return description;
    }
}
