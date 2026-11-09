package com.cffex.rag.common.domain.terminology;

import java.time.Instant;

/**
 * 术语规则运行状态，用于确认特性是否真正完成加载，而不只是配置了启用开关。
 */
public record TerminologyStatus(
        boolean enabled,
        boolean loaded,
        long version,
        Instant lastRefreshAttemptAt,
        Instant lastSuccessfulLoadAt,
        int globalRuleCount,
        int knowledgeBaseCount,
        int knowledgeBaseRuleCount,
        String lastError
) {
    public static TerminologyStatus disabled() {
        return new TerminologyStatus(false, false, 0, null, null, 0, 0, 0, null);
    }
}
