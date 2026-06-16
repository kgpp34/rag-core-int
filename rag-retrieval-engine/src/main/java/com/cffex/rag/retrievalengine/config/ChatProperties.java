package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.chat")
public record ChatProperties(
        boolean memoryEnabled,
        int maxContextTokens,
        int reservedPromptTokens,
        boolean summaryEnabled,
        int summaryTriggerMessages,
        int summaryRetainRecentMessages,
        int summaryMaxTokens,
        boolean retrievalAugmentationAdvisorEnabled
) {
}
