package com.cffex.rag.common.domain.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RetrievalResult(
        String requestId,
        List<RetrievedChunk> chunks,
        Map<String, Object> debugTrace
) {
    public RetrievalResult {
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        debugTrace = Map.copyOf(Objects.requireNonNull(debugTrace, "debugTrace must not be null"));
    }
}
