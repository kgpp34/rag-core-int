package com.cffex.rag.retrievalengine.domain.port;

import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;

/**
 * 查询向量化出站端口。
 */
public interface QueryEmbeddingPort {

    /**
     * 将查询文本向量化为 dense 检索可直接复用的 query vector。
     */
    float[] embed(String queryText, EmbeddingModelPolicy embeddingModel);
}
