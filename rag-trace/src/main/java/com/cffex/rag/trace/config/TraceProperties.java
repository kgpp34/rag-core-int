package com.cffex.rag.trace.config;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.trace")
public class TraceProperties {

    private boolean enabled;
    private Mode mode = Mode.SUMMARY;
    private boolean includeSensitivePayload;
    private int textPreviewLength = 200;
    private int maxPayloadBytes = 65536;
    private int maxCandidatesPerEvent = 20;
    private double sampleRate = 1.0d;
    private final Sinks sinks = new Sinks();

    public enum Mode {
        OFF,
        SUMMARY,
        DETAIL,
        DEBUG
    }

    public enum OverflowPolicy {
        DROP_OLDEST,
        DROP_NEWEST,
        BLOCK
    }

    public static class Sinks {
        private final Jdbc jdbc = new Jdbc();
        private final File file = new File();

        public Jdbc getJdbc() {
            return jdbc;
        }

        public File getFile() {
            return file;
        }
    }

    public static class Jdbc {
        private boolean enabled;
        private int queueCapacity = 10000;
        private int batchSize = 100;
        private long flushIntervalMs = 1000;
        private long shutdownTimeoutMs = 5000;
        private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_OLDEST;
        private long offerTimeoutMs;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = positive(queueCapacity, 10000);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = positive(batchSize, 100);
        }

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = positive(flushIntervalMs, 1000);
        }

        public long getShutdownTimeoutMs() {
            return shutdownTimeoutMs;
        }

        public void setShutdownTimeoutMs(long shutdownTimeoutMs) {
            this.shutdownTimeoutMs = positive(shutdownTimeoutMs, 5000);
        }

        public OverflowPolicy getOverflowPolicy() {
            return overflowPolicy;
        }

        public void setOverflowPolicy(OverflowPolicy overflowPolicy) {
            this.overflowPolicy = overflowPolicy == null ? OverflowPolicy.DROP_OLDEST : overflowPolicy;
        }

        public void setOverflowPolicy(String overflowPolicy) {
            if (overflowPolicy == null || overflowPolicy.isBlank()) {
                this.overflowPolicy = OverflowPolicy.DROP_OLDEST;
                return;
            }
            this.overflowPolicy = OverflowPolicy.valueOf(overflowPolicy.trim().replace('-', '_')
                    .toUpperCase(Locale.ROOT));
        }

        public long getOfferTimeoutMs() {
            return offerTimeoutMs;
        }

        public void setOfferTimeoutMs(long offerTimeoutMs) {
            this.offerTimeoutMs = Math.max(0, offerTimeoutMs);
        }
    }

    public static class File {
        private boolean enabled;
        private String outputDir = "/tmp/retrieval-traces/rag-core-int";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOutputDir() {
            return outputDir;
        }

        public void setOutputDir(String outputDir) {
            if (outputDir != null && !outputDir.isBlank()) {
                this.outputDir = outputDir;
            }
        }
    }

    public boolean isEnabled() {
        return enabled && mode != Mode.OFF;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.SUMMARY : mode;
    }

    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) {
            this.mode = Mode.SUMMARY;
            return;
        }
        this.mode = Mode.valueOf(mode.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    public boolean isIncludeSensitivePayload() {
        return includeSensitivePayload;
    }

    public void setIncludeSensitivePayload(boolean includeSensitivePayload) {
        this.includeSensitivePayload = includeSensitivePayload;
    }

    public int getTextPreviewLength() {
        return textPreviewLength;
    }

    public void setTextPreviewLength(int textPreviewLength) {
        this.textPreviewLength = positive(textPreviewLength, 200);
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = positive(maxPayloadBytes, 65536);
    }

    public int getMaxCandidatesPerEvent() {
        return maxCandidatesPerEvent;
    }

    public void setMaxCandidatesPerEvent(int maxCandidatesPerEvent) {
        this.maxCandidatesPerEvent = positive(maxCandidatesPerEvent, 20);
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
    }

    public Sinks getSinks() {
        return sinks;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
