package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.MDC;
import org.springframework.ai.chat.messages.Message;

import com.cffex.rag.trace.application.TraceRecorder;
import com.cffex.rag.trace.config.TraceProperties;

final class LlmMessageTraceSupport {

    private static final String EVENT_NAME = "llm.messages.final";

    private final TraceRecorder traceRecorder;
    private final TraceProperties traceProperties;

    LlmMessageTraceSupport(TraceRecorder traceRecorder, TraceProperties traceProperties) {
        this.traceRecorder = Objects.requireNonNull(traceRecorder);
        this.traceProperties = Objects.requireNonNull(traceProperties);
    }

    void recordFinalMessages(
            String traceId,
            String conversationId,
            List<Message> memoryMessages,
            List<Message> currentInstructions,
            List<Message> finalMessages
    ) {
        if (!shouldRecord()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageCount", finalMessages.size());
        payload.put("memoryMessageCount", memoryMessages.size());
        payload.put("currentInstructionCount", currentInstructions.size());
        payload.put("messageTypes", finalMessages.stream().map(message -> message.getMessageType().name()).toList());
        payload.put("messages", messageSummaries(finalMessages));
        traceRecorder.record(
                firstNonBlank(traceId, MDC.get("traceId"), "unknown"),
                null,
                "llm",
                "answer_generation",
                EVENT_NAME,
                payload
        );
    }

    private boolean shouldRecord() {
        if (!traceRecorder.enabled() || !traceProperties.isEnabled()) {
            return false;
        }
        return switch (traceProperties.getMode()) {
            case OFF -> false;
            case SUMMARY, DETAIL, DEBUG -> true;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private List<Map<String, Object>> messageSummaries(List<Message> messages) {
        List<Map<String, Object>> rows = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String text = message.getText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("type", message.getMessageType().name());
            row.put("length", text == null ? 0 : text.length());
            row.put("preview", preview(text));
            if (traceProperties.isIncludeSensitivePayload()) {
                row.put("content", Objects.toString(text, ""));
            }
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private String preview(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int maxLength = Math.max(1, traceProperties.getTextPreviewLength());
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
