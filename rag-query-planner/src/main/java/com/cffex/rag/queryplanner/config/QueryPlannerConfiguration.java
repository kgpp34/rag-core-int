package com.cffex.rag.queryplanner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueryPlannerProperties.class)
public class QueryPlannerConfiguration {
}
