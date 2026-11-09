package com.cffex.rag.common.domain.terminology;

import java.util.Objects;

/**
 * 一次术语替换命中记录：query 中的口语 {@code colloquial} 被替换为专业术语 {@code term}。
 *
 * @param colloquial       命中的口语原文（来自 query 文本）
 * @param term             替换后的专业术语
 * @param scope            规则粒度："global" 或 "single_kb"
 * @param knowledgeBaseId  scope 为 "single_kb" 时的知识库 id，全局规则为 null
 */
public record TerminologyReplacement(
        String colloquial,
        String term,
        String scope,
        String knowledgeBaseId
) {
    public TerminologyReplacement {
        colloquial = Objects.requireNonNull(colloquial, "colloquial must not be null");
        term = Objects.requireNonNull(term, "term must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
    }
}
