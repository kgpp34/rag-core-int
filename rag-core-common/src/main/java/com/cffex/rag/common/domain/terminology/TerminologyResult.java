package com.cffex.rag.common.domain.terminology;

import java.util.List;
import java.util.Objects;

/**
 * 术语替换结果：替换后的文本 + 是否发生变化 + 命中的替换明细。
 *
 * <p>替换未命中任何规则时返回 {@link #identity(String)}（changed=false、applied 为空、text 原样返回）。
 */
public record TerminologyResult(
        String text,
        boolean changed,
        List<TerminologyReplacement> applied
) {
    public TerminologyResult {
        text = Objects.requireNonNull(text, "text must not be null");
        applied = List.copyOf(Objects.requireNonNull(applied, "applied must not be null"));
    }

    public static TerminologyResult identity(String text) {
        return new TerminologyResult(text, false, List.of());
    }
}
