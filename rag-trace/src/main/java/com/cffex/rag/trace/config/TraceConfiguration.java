package com.cffex.rag.trace.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cffex.rag.trace.application.AsyncTraceRecorder;
import com.cffex.rag.trace.application.NoOpTraceRecorder;
import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.domain.TraceSink;
import com.cffex.rag.trace.infrastructure.file.JsonlTraceSink;
import com.cffex.rag.trace.infrastructure.jdbc.JdbcTraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TraceConfiguration.class);

    @Bean(name = "traceJdbcTemplate")
    @ConditionalOnMissingBean(name = "traceJdbcTemplate")
    public JdbcTemplate traceJdbcTemplate(
            @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
            Environment environment
    ) {
        validateTraceDataSource(environment);
        log.info("RAG trace JDBC sink 绑定 Spring 主业务库 | spring.datasource.url={}",
                environment.getProperty("spring.datasource.url", "<unset>"));
        return businessJdbcTemplate;
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceRecorder traceRecorder(
            TraceProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("traceJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcTemplateProvider
    ) {
        List<TraceSink> traceSinks = buildSinks(properties, objectMapper, jdbcTemplateProvider.getIfAvailable());
        if (!properties.isEnabled() || traceSinks.isEmpty()) {
            return new NoOpTraceRecorder();
        }
        return new AsyncTraceRecorder(properties, traceSinks);
    }

    private List<TraceSink> buildSinks(
            TraceProperties properties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        List<TraceSink> sinks = new ArrayList<>();
        if (properties.isEnabled() && properties.getSinks().getJdbc().isEnabled() && jdbcTemplate != null) {
            sinks.add(new JdbcTraceSink(jdbcTemplate, objectMapper));
        }
        if (properties.isEnabled() && properties.getSinks().getFile().isEnabled()) {
            sinks.add(new JsonlTraceSink(properties.getSinks().getFile().getOutputDir(), objectMapper));
        }
        return sinks;
    }

    private static void validateTraceDataSource(Environment environment) {
        String businessUrl = normalize(environment.getProperty("spring.datasource.url"));
        String difyUrl = normalize(environment.getProperty("rag.datasource.metadata.dify.url"));
        if (businessUrl == null) {
            throw new IllegalStateException("RAG trace JDBC sink requires spring.datasource.url");
        }
        if (difyUrl != null && businessUrl.equals(difyUrl)) {
            throw new IllegalStateException(
                    "RAG trace JDBC sink must use spring.datasource, but spring.datasource.url equals "
                            + "rag.datasource.metadata.dify.url: " + businessUrl);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
