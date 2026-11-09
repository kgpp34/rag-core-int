package com.cffex.rag.metadatacacher.application;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.terminology.TerminologyResult;
import com.cffex.rag.common.domain.terminology.TerminologyStatus;
import com.cffex.rag.common.service.TerminologyService;
import com.cffex.rag.metadatacacher.config.TerminologyReplacementProperties;
import com.cffex.rag.metadatacacher.domain.terminology.TerminologyRecord;
import com.cffex.rag.metadatacacher.domain.terminology.TerminologyRuleSet;
import com.cffex.rag.metadatacacher.domain.terminology.TerminologySnapshot;
import com.cffex.rag.metadatacacher.domain.terminology.TerminologySnapshotFactory;

/**
 * 术语替换默认实现：内存快照 + 原子换引用，无锁读。
 *
 * <p>特性关闭或快照为空（未加载/加载失败）时一律返回恒等结果，检索不受影响。
 */
@Service
public class DefaultTerminologyService implements TerminologyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTerminologyService.class);

    private final TerminologyReplacementProperties properties;
    private final AtomicReference<TerminologySnapshot> snapshot = new AtomicReference<>(TerminologySnapshot.empty());
    private final AtomicLong version = new AtomicLong();
    private final AtomicReference<RefreshState> refreshState = new AtomicReference<>(RefreshState.empty());

    public DefaultTerminologyService(TerminologyReplacementProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public TerminologyResult replaceGlobal(String query) {
        if (!isEnabled()) {
            return TerminologyResult.identity(query);
        }
        return snapshot.get().global().apply(query, TerminologySnapshotFactory.SCOPE_GLOBAL, null);
    }

    @Override
    public TerminologyResult replaceForKnowledgeBase(String query, String knowledgeBaseId) {
        if (!isEnabled()) {
            return TerminologyResult.identity(query);
        }
        TerminologyRuleSet ruleSet = knowledgeBaseId == null
                ? TerminologyRuleSet.empty()
                : snapshot.get().byKbId().getOrDefault(knowledgeBaseId, TerminologyRuleSet.empty());
        return ruleSet.apply(query, TerminologySnapshotFactory.SCOPE_SINGLE_KB, knowledgeBaseId);
    }

    public void reload(List<TerminologyRecord> records) {
        Instant attemptAt = Instant.now();
        long nextVersion = version.incrementAndGet();
        TerminologySnapshot next = TerminologySnapshotFactory.create(
                nextVersion,
                records == null ? List.of() : records
        );
        snapshot.set(next);
        refreshState.set(new RefreshState(attemptAt, next.loadedAt(), null));
        log.info("术语快照已刷新，version={}，全局规则数={}，KB数={}，KB规则总数={}",
                nextVersion,
                next.global().rules().size(),
                next.byKbId().size(),
                next.byKbId().values().stream().mapToInt(set -> set.rules().size()).sum());
    }

    public void recordRefreshFailure(RuntimeException failure) {
        RefreshState previous = refreshState.get();
        refreshState.set(new RefreshState(
                Instant.now(),
                previous.lastSuccessfulLoadAt(),
                failure == null ? "unknown error" : summarize(failure)
        ));
    }

    @Override
    public TerminologyStatus status() {
        TerminologySnapshot current = snapshot.get();
        RefreshState state = refreshState.get();
        return new TerminologyStatus(
                isEnabled(),
                current.version() > 0,
                current.version(),
                state.lastRefreshAttemptAt(),
                state.lastSuccessfulLoadAt(),
                current.global().rules().size(),
                current.byKbId().size(),
                current.byKbId().values().stream().mapToInt(set -> set.rules().size()).sum(),
                state.lastError()
        );
    }

    private static String summarize(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record RefreshState(
            Instant lastRefreshAttemptAt,
            Instant lastSuccessfulLoadAt,
            String lastError
    ) {
        private static RefreshState empty() {
            return new RefreshState(null, null, null);
        }
    }
}
