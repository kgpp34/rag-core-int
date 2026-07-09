package com.cffex.rag.retrievalengine.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cffex.rag.retrievalengine.infrastructure.chat.springai.ChatAdvisorContributor;
import com.cffex.rag.retrievalengine.infrastructure.chat.springai.ConversationSummarizer;
import com.cffex.rag.retrievalengine.infrastructure.chat.springai.ConversationSummaryRepository;
import com.cffex.rag.retrievalengine.infrastructure.chat.springai.ConversationSummaryService;
import com.cffex.rag.retrievalengine.infrastructure.chat.springai.ChatMemoryAdvisorContributor;
import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;

@Configuration
@ConditionalOnProperty(name = "rag.chat.memory-enabled", havingValue = "true")
public class ChatMemoryConfiguration {

    @Bean
    ChatAdvisorContributor chatMemoryAdvisorContributor(
            ChatMemoryRepository chatMemoryRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummaryService summaryService,
            ChatProperties properties,
            TraceRecorder traceRecorder,
            TraceProperties traceProperties) {
        return new ChatMemoryAdvisorContributor(
                chatMemoryRepository,
                summaryRepository,
                summaryService,
                properties,
                traceRecorder,
                traceProperties
        );
    }

    @Bean(destroyMethod = "close")
    ExecutorService conversationSummaryExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ConversationSummaryService conversationSummaryService(
            ChatMemoryRepository chatMemoryRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationSummarizer summarizer,
            ChatProperties properties,
            ExecutorService conversationSummaryExecutor
    ) {
        return new ConversationSummaryService(
                chatMemoryRepository,
                summaryRepository,
                summarizer,
                properties,
                conversationSummaryExecutor
        );
    }
}
