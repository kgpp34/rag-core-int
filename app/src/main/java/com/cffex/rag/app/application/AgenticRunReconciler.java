package com.cffex.rag.app.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.service.ConversationMemoryService;
import com.fasterxml.jackson.databind.JsonNode;

/** Repairs core-side run state and retries durable conversation-memory writes. */
@Component
final class AgenticRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(AgenticRunReconciler.class);

    private final AgenticRunStore runStore;
    private final AgenticRagClient agenticClient;
    private final ConversationMemoryService memoryService;
    private final AppProperties.Agentic properties;

    AgenticRunReconciler(
            AgenticRunStore runStore,
            AgenticRagClient agenticClient,
            ConversationMemoryService memoryService,
            AppProperties appProperties
    ) {
        this.runStore = runStore;
        this.agenticClient = agenticClient;
        this.memoryService = memoryService;
        this.properties = appProperties.getRag().getAgentic();
    }

    @Scheduled(
            initialDelayString = "${app.rag.agentic.reconciliation-fixed-delay:30s}",
            fixedDelayString = "${app.rag.agentic.reconciliation-fixed-delay:30s}"
    )
    void reconcile() {
        if (!properties.isEnabled() || !properties.isReconciliationEnabled()) {
            return;
        }
        Instant staleBefore = Instant.now().minus(properties.getReconciliationStaleAfter());
        List<AgenticRun> candidates = runStore.findRecoverable(
                staleBefore,
                properties.getMemoryMaxAttempts(),
                properties.getReconciliationBatchSize()
        );
        if (!candidates.isEmpty()) {
            log.info("开始 Agentic Run 对账，candidateCount={}", candidates.size());
        }
        for (AgenticRun run : candidates) {
            try {
                reconcile(run, staleBefore);
            } catch (RuntimeException ex) {
                log.warn("Agentic Run 对账失败，runId={}，status={}，message={}",
                        run.runId(), run.status(), ex.getMessage(), ex);
            }
        }
    }

    private void reconcile(AgenticRun run, Instant staleBefore) {
        JsonNode output = run.output();
        if ("queued".equals(run.status()) || "running".equals(run.status())) {
            AgenticRagClient.AgenticRunResult remote = agenticClient.getRun(run.runId(), run.requestId());
            switch (remote.status()) {
                case "queued" -> {
                    return;
                }
                case "running" -> {
                    runStore.markRunning(run.runId());
                    return;
                }
                case "completed" -> {
                    output = remote.output();
                    runStore.markCompleted(run.runId(), output);
                }
                case "failed" -> {
                    runStore.markFailed(run.runId(), remote.error());
                    return;
                }
                case "cancelled" -> {
                    runStore.markCancelled(run.runId());
                    return;
                }
                default -> {
                    log.warn("忽略未知 Agentic Run 状态，runId={}，remoteStatus={}", run.runId(), remote.status());
                    return;
                }
            }
        }
        writeMemory(run.runId(), run.conversationId(), run.query(), output, staleBefore);
    }

    private void writeMemory(
            java.util.UUID runId,
            String conversationId,
            String query,
            JsonNode output,
            Instant staleBefore
    ) {
        if (conversationId == null || output == null || !output.isObject()
                || !runStore.claimMemoryWrite(runId, staleBefore)) {
            return;
        }
        try {
            memoryService.appendExchangeOnce(
                    runId.toString(),
                    conversationId,
                    query,
                    output.path("answer").asText("")
            );
            runStore.markMemoryWritten(runId);
            log.info("Agentic Run 记忆补偿完成，runId={}，conversationId={}", runId, conversationId);
        } catch (RuntimeException ex) {
            runStore.markMemoryWriteFailed(runId, summarize(ex));
            throw ex;
        }
    }

    private static String summarize(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
