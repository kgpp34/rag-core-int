package com.cffex.rag.trace.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class TraceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestDataSourceConfiguration.class, TraceConfiguration.class)
            .withPropertyValues(
                    "rag.trace.enabled=true",
                    "rag.trace.sinks.jdbc.enabled=true",
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/rag",
                    "rag.datasource.metadata.dify.url=jdbc:postgresql://localhost:5432/dify"
            );

    @Test
    void traceJdbcTemplateUsesSpringPrimaryBusinessDataSource() {
        contextRunner.run(context -> {
            DataSource businessDataSource = context.getBean("dataSource", DataSource.class);
            DataSource difyDataSource = context.getBean("difyDataSource", DataSource.class);
            JdbcTemplate businessJdbcTemplate = context.getBean("businessJdbcTemplate", JdbcTemplate.class);
            JdbcTemplate traceJdbcTemplate = context.getBean("traceJdbcTemplate", JdbcTemplate.class);

            assertThat(traceJdbcTemplate).isSameAs(businessJdbcTemplate);
            assertThat(traceJdbcTemplate.getDataSource()).isSameAs(businessDataSource);
            assertThat(traceJdbcTemplate.getDataSource()).isNotSameAs(difyDataSource);
        });
    }

    @Test
    void traceJdbcTemplateFailsFastWhenBusinessDataSourcePointsToDify() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/dify",
                        "rag.datasource.metadata.dify.url=jdbc:postgresql://localhost:5432/dify"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {

        @Bean
        DataSource dataSource() {
            return new MarkerDataSource();
        }

        @Bean
        DataSource difyDataSource() {
            return new MarkerDataSource();
        }

        @Bean
        JdbcTemplate businessJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("dataSource") DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    static class MarkerDataSource extends AbstractDataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("MarkerDataSource does not provide connections");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("MarkerDataSource does not provide connections");
        }
    }
}
