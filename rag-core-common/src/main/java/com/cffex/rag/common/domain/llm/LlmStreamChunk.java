package com.cffex.rag.common.domain.llm;

import java.util.Map;
import java.util.Objects;

/** LLM 流式输出中的单个增量片段。 */
public record LlmStreamChunk(
        String delta,
        String finishReason,
        boolean completed,
        Map<String, Object> metadata
) {
    public LlmStreamChunk {
        delta = delta == null ? "" : delta;
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
