package com.cffex.rag.retrievalengine.domain.port;

import java.util.List;

import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 全文检索出站端口。
 */
public interface FullTextSearchPort {

    /**
     * 当前端口对应的存储引擎标识。
     */
    String engineId();

    /**
     * 执行全文检索并返回候选结果。
     */
    List<RetrievalCandidate> search(FullTextSearchRequest request);
}
