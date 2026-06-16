package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifyDatasetMapper {

    @Select("""
            SELECT
                id AS dataset_id,
                name AS dataset_name,
                COALESCE(retrieval_model ->> 'search_method', 'semantic_search') AS retrieval_mode,
                COALESCE(index_struct::jsonb -> 'vector_store' ->> 'class_prefix', id::text) AS collection_name,
                TRUE AS enabled,
                NULLIF(retrieval_model ->> 'top_k', '')::integer AS top_k,
                COALESCE((retrieval_model ->> 'reranking_enable')::boolean, FALSE)         AS reranking_enabled,
                COALESCE((retrieval_model ->> 'score_threshold_enabled')::boolean, FALSE)  AS score_threshold_enabled,
                COALESCE((retrieval_model ->> 'score_threshold')::float, 0.0)              AS score_threshold,
                COALESCE((retrieval_model -> 'weights' -> 'vector_setting' ->> 'vector_weight')::float, 0.7) AS vector_weight,
                COALESCE((retrieval_model -> 'weights' -> 'keyword_setting' ->> 'keyword_weight')::float, 0.3) AS keyword_weight
            FROM public.datasets
            """)
    List<DatasetRow> scanAllDatasets();

    record DatasetRow(
            String datasetId,
            String datasetName,
            String retrievalMode,
            String collectionName,
            boolean enabled,
            Integer topK,
            boolean rerankingEnabled,
            boolean scoreThresholdEnabled,
            double scoreThreshold,
            double vectorWeight,
            double keywordWeight
    ) {}
}
