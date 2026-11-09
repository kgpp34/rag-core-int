package com.cffex.rag.metadatacacher.domain.terminology;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 术语快照：一次全量加载的不可变规则集，AtomicReference 原子换引用实现无锁读。
 */
public record TerminologySnapshot(
        long version,
        Instant loadedAt,
        TerminologyRuleSet global,
        Map<String, TerminologyRuleSet> byKbId
) {
    public TerminologySnapshot {
        global = global == null ? TerminologyRuleSet.empty() : global;
        byKbId = Map.copyOf(Objects.requireNonNullElse(byKbId, Map.of()));
    }

    public static TerminologySnapshot empty() {
        return new TerminologySnapshot(0, Instant.EPOCH, TerminologyRuleSet.empty(), Map.of());
    }
}
