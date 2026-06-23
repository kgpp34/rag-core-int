package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.chat")
public record ChatProperties(
        boolean memoryEnabled,
        int memoryWindowTurns,
        int maxRequestContextTokens,
        double memoryTokenBudgetRatio,
        boolean summaryEnabled,
        int summaryTriggerTurns,
        int summaryMaxTokens,
        boolean retrievalAugmentationAdvisorEnabled
) {
}
