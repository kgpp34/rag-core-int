package com.cffex.rag.retrievalengine.domain.port;

import java.util.List;

import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 向量检索出站端口。
 */
public interface VectorSearchPort {

    /**
     * 当前端口对应的存储引擎标识。
     */
    String engineId();

    /**
     * 执行向量检索并返回候选结果。
     */
    List<RetrievalCandidate> search(DenseVectorSearchRequest request);
}
