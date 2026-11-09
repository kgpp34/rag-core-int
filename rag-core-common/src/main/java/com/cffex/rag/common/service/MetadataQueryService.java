package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import com.cffex.rag.common.domain.metadata.SegmentMeta;
import java.util.List;
import java.util.Map;

public interface MetadataQueryService {
    List<ModelMeta> listModels(ModelQueryCondition condition);

    List<KnowledgeBaseMeta> listKnowledgeBases(KnowledgeBaseQueryCondition condition);

    Map<String, DocumentMeta> getDocumentMetas(List<String> documentIds);

    /**
     * 按 Dify 分段索引节点 ID 批量查询分段元数据（position 等）。
     *
     * <p>默认返回空 Map，未实现该能力的实现方视为缓存不可用。
     */
    default Map<String, SegmentMeta> getSegmentMetas(List<String> indexNodeIds) {
        return Map.of();
    }
}
