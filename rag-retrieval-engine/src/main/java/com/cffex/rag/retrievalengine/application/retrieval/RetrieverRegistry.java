package com.cffex.rag.retrievalengine.application.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.domain.port.FullTextSearchPort;
import com.cffex.rag.retrievalengine.domain.port.VectorSearchPort;

/**
 * 检索端口注册表。
 *
 * <p>按 engineId 对现有 dense/full-text 端口做统一注册，
 * 让执行链只依赖检索能力和引擎标识，不依赖具体基础设施实现。
 */
@Component
public class RetrieverRegistry {

    private final Map<String, VectorSearchPort> vectorPorts;
    private final Map<String, FullTextSearchPort> fullTextPorts;

    public RetrieverRegistry(List<VectorSearchPort> vectorSearchPorts, List<FullTextSearchPort> fullTextSearchPorts) {
        this.vectorPorts = indexVectorPorts(vectorSearchPorts);
        this.fullTextPorts = indexFullTextPorts(fullTextSearchPorts);
    }

    public VectorSearchPort vectorSearchPort(String engineId) {
        // 检索任务只关心“这个 engineId 对应哪个向量检索实现”，
        // 具体是 Milvus 还是别的数据源，都在注册表后面解耦掉。
        VectorSearchPort port = vectorPorts.get(engineId);
        if (port == null) {
            throw new UnsupportedOperationException(
                    "No storage port supports engineId=%s, requestType=DenseVectorSearchRequest".formatted(engineId)
            );
        }
        return port;
    }

    public FullTextSearchPort fullTextSearchPort(String engineId) {
        // 全文检索同理：上层只声明能力和 engineId，不直接依赖某个具体实现类。
        FullTextSearchPort port = fullTextPorts.get(engineId);
        if (port == null) {
            throw new UnsupportedOperationException(
                    "No storage port supports engineId=%s, requestType=FullTextSearchRequest".formatted(engineId)
            );
        }
        return port;
    }

    private static Map<String, VectorSearchPort> indexVectorPorts(List<VectorSearchPort> ports) {
        Map<String, VectorSearchPort> indexed = new LinkedHashMap<>();
        for (VectorSearchPort port : ports) {
            VectorSearchPort previous = indexed.putIfAbsent(port.engineId(), port);
            if (previous != null) {
                throw new IllegalStateException("Duplicate vector search port engineId=%s".formatted(port.engineId()));
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, FullTextSearchPort> indexFullTextPorts(List<FullTextSearchPort> ports) {
        Map<String, FullTextSearchPort> indexed = new LinkedHashMap<>();
        for (FullTextSearchPort port : ports) {
            FullTextSearchPort previous = indexed.putIfAbsent(port.engineId(), port);
            if (previous != null) {
                throw new IllegalStateException("Duplicate full text search port engineId=%s".formatted(port.engineId()));
            }
        }
        return Map.copyOf(indexed);
    }
}
