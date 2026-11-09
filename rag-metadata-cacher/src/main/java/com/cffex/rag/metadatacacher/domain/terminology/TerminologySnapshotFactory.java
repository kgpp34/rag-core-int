package com.cffex.rag.metadatacacher.domain.terminology;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将 {@link TerminologyRecord} 行集合解析为 {@link TerminologySnapshot}。
 *
 * <p>脏数据（desc 非法 JSON、未知 scope、single_kb 缺 kb_id、空白术语/口语）一律跳过并告警，
 * 检索永不因术语数据问题失败。
 */
public final class TerminologySnapshotFactory {

    private static final Logger log = LoggerFactory.getLogger(TerminologySnapshotFactory.class);

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_SINGLE_KB = "single_kb";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TerminologySnapshotFactory() {
    }

    public static TerminologySnapshot create(long version, List<TerminologyRecord> records) {
        List<TerminologyRule> globalRules = new ArrayList<>();
        Map<String, List<TerminologyRule>> rulesByKbId = new LinkedHashMap<>();
        if (records != null) {
            for (TerminologyRecord record : records) {
                appendRule(record, globalRules, rulesByKbId);
            }
        }
        Map<String, TerminologyRuleSet> byKbId = new LinkedHashMap<>();
        rulesByKbId.forEach((kbId, rules) -> byKbId.put(kbId, TerminologyRuleSet.of(rules)));
        return new TerminologySnapshot(version, Instant.now(), TerminologyRuleSet.of(globalRules), byKbId);
    }

    private static void appendRule(
            TerminologyRecord record,
            List<TerminologyRule> globalRules,
            Map<String, List<TerminologyRule>> rulesByKbId
    ) {
        String term = normalize(record.term());
        if (term == null) {
            log.warn("术语记录缺少术语，跳过，id={}", record.id());
            return;
        }
        String scope = normalize(record.scope());
        String kbId = normalize(record.kbId());
        List<TerminologyRule> target;
        if (SCOPE_GLOBAL.equals(scope)) {
            target = globalRules;
        } else if (SCOPE_SINGLE_KB.equals(scope)) {
            if (kbId == null) {
                log.warn("single_kb 术语记录缺少 kb_id，跳过，id={}", record.id());
                return;
            }
            target = rulesByKbId.computeIfAbsent(kbId, ignored -> new ArrayList<>());
        } else {
            log.warn("术语记录 scope 非法，跳过，id={}，scope={}", record.id(), record.scope());
            return;
        }
        List<String> colloquials = parseColloquials(record.id(), record.descJson());
        for (String colloquial : colloquials) {
            target.add(new TerminologyRule(term, colloquial));
        }
    }

    private static List<String> parseColloquials(long id, String descJson) {
        if (descJson == null || descJson.isBlank()) {
            log.warn("术语记录 desc 为空，跳过，id={}", id);
            return List.of();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(descJson);
            if (!node.isArray()) {
                log.warn("术语记录 desc 不是 JSON 数组，跳过，id={}，desc={}", id, descJson);
                return List.of();
            }
            List<String> colloquials = new ArrayList<>();
            for (JsonNode element : node) {
                if (!element.isTextual()) {
                    log.warn("术语记录 desc 含非文本元素，忽略该元素，id={}，element={}", id, element);
                    continue;
                }
                String colloquial = normalize(element.asText());
                if (colloquial != null) {
                    colloquials.add(colloquial);
                }
            }
            if (colloquials.isEmpty()) {
                log.warn("术语记录 desc 无有效口语，跳过，id={}", id);
            }
            return colloquials;
        } catch (Exception ex) {
            log.warn("术语记录 desc 解析失败，跳过，id={}，desc={}", id, descJson, ex);
            return List.of();
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
