package com.cffex.rag.metadatacacher.config;

import com.cffex.rag.metadatacacher.consts.MetadataSchedulerConstants;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metadata-cacher")
public class MetadataCacherProperties {

    private CacheType cacheType = CacheType.IN_MEMORY;
    private boolean warmupEnabled = true;
    private boolean warmupFailFast = false;
    private final Dify dify = new Dify();
    private final Scheduler scheduler = new Scheduler();

    public CacheType getCacheType() {
        return cacheType;
    }

    public void setCacheType(CacheType cacheType) {
        this.cacheType = cacheType;
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }

    public boolean isWarmupFailFast() {
        return warmupFailFast;
    }

    public void setWarmupFailFast(boolean warmupFailFast) {
        this.warmupFailFast = warmupFailFast;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Dify getDify() {
        return dify;
    }

    public enum CacheType {
        IN_MEMORY,
        REDIS
    }

    public static class Dify {
        private String defaultTenantId = "default";
        private String defaultApiKey;
        private List<DomainMapping> domainMappings = new ArrayList<>();

        public String getDefaultTenantId() {
            return defaultTenantId;
        }

        public void setDefaultTenantId(String defaultTenantId) {
            this.defaultTenantId = defaultTenantId;
        }

        public String getDefaultApiKey() {
            return defaultApiKey;
        }

        public void setDefaultApiKey(String defaultApiKey) {
            this.defaultApiKey = defaultApiKey;
        }

        public List<DomainMapping> getDomainMappings() {
            return domainMappings;
        }

        public void setDomainMappings(List<DomainMapping> domainMappings) {
            this.domainMappings = domainMappings;
        }
    }

    public static class DomainMapping {
        private String domainCode;
        private List<String> tagCodes = new ArrayList<>();

        public String getDomainCode() {
            return domainCode;
        }

        public void setDomainCode(String domainCode) {
            this.domainCode = domainCode;
        }

        public List<String> getTagCodes() {
            return tagCodes;
        }

        public void setTagCodes(List<String> tagCodes) {
            this.tagCodes = tagCodes;
        }
    }

    public static class Scheduler {
        private final DifyDb difyDb = new DifyDb();

        public DifyDb getDifyDb() {
            return difyDb;
        }
    }

    public static class DifyDb {
        private long initialDelayMs = MetadataSchedulerConstants.DEFAULT_DIFY_REFRESH_INITIAL_DELAY_MS;
        private long fixedDelayMs = MetadataSchedulerConstants.DEFAULT_DIFY_REFRESH_FIXED_DELAY_MS;

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
