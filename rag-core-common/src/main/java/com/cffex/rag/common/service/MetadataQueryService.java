package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import java.util.List;
import java.util.Map;

public interface MetadataQueryService {
    List<ModelMeta> listModels(ModelQueryCondition condition);

    List<KnowledgeBaseMeta> listKnowledgeBases(KnowledgeBaseQueryCondition condition);

    Map<String, DocumentMeta> getDocumentMetas(List<String> documentIds);
}
