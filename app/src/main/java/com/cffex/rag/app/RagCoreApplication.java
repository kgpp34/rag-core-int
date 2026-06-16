package com.cffex.rag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan(basePackages = "com.cffex.rag")
@SpringBootApplication(scanBasePackages = "com.cffex.rag")
public class RagCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagCoreApplication.class, args);
    }
}
