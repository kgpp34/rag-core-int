package com.cffex.rag.metadatacacher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索术语替换特性配置（特性门控：仅全局开关，无请求级覆盖）。
 */
@ConfigurationProperties(prefix = "rag.terminology-replacement")
public class TerminologyReplacementProperties {

    /** 是否启用术语替换，默认关闭。 */
    private boolean enabled = false;

    /** 启动预热后首次定时刷新的延迟毫秒数。 */
    private long initialDelayMs = 300_000L;

    /** 定时刷新间隔毫秒数。 */
    private long refreshIntervalMs = 300_000L;

    /** 是否在启动时确保术语表和索引存在。托管数据库可关闭并改用外部迁移。 */
    private boolean initializeSchema = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }
}
