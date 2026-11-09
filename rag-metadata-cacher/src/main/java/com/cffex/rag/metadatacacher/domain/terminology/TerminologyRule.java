package com.cffex.rag.metadatacacher.domain.terminology;

import java.util.Objects;

/**
 * 单条术语替换规则：口语 {@code colloquial} 命中后替换为专业术语 {@code term}。
 */
public record TerminologyRule(String term, String colloquial) {
    public TerminologyRule {
        term = Objects.requireNonNull(term, "term must not be null");
        colloquial = Objects.requireNonNull(colloquial, "colloquial must not be null");
    }
}
