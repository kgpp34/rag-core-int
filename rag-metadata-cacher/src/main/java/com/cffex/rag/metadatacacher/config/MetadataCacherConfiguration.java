package com.cffex.rag.metadatacacher.config;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.cffex.rag.metadatacacher.domain.port.MetadataSnapshotStore;
import com.cffex.rag.metadatacacher.infrastructure.cache.InMemoryMetadataSnapshotStore;

@Configuration
@EnableScheduling
public class MetadataCacherConfiguration {

    @Bean
    public MetadataSnapshotStore metadataSnapshotStore(MetadataCacherProperties properties) {
        return switch (properties.getCacheType()) {
            case IN_MEMORY -> new InMemoryMetadataSnapshotStore();
            case REDIS -> throw new IllegalStateException("metadata-cacher cacheType=REDIS is not implemented yet");
        };
    }

    @Configuration
    @ConditionalOnProperty(name = "rag.datasource.metadata.dify.enabled", havingValue = "true", matchIfMissing = true)
    static class DifyDataSourceConfiguration {

        @Bean
        @ConfigurationProperties("rag.datasource.metadata.dify")
        public DataSourceProperties difyDataSourceProperties() {
            return new DataSourceProperties();
        }

        @Bean(name = "difyDataSource")
        public DataSource difyDataSource(@Qualifier("difyDataSourceProperties") DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().build();
        }

        @Bean(name = "difySqlSessionFactory")
        public SqlSessionFactory difySqlSessionFactory(
                @Qualifier("difyDataSource") DataSource dataSource
        ) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setVfs(SpringBootVFS.class);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factoryBean.setConfiguration(configuration);
            return factoryBean.getObject();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "rag.datasource.metadata.dify.enabled", havingValue = "true", matchIfMissing = true)
    @MapperScan(
            basePackages = "com.cffex.rag.metadatacacher.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "difySqlSessionFactory"
    )
    static class DifyMapperConfiguration {
    }

}
