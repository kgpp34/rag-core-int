package com.cffex.rag.metadatacacher.application;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.terminology.TerminologyResult;
import com.cffex.rag.metadatacacher.config.TerminologyReplacementProperties;
import com.cffex.rag.metadatacacher.domain.terminology.TerminologyRecord;

class DefaultTerminologyServiceTest {

    @Test
    void disabled_returnsIdentityEvenWhenRulesLoaded() {
        DefaultTerminologyService service = new DefaultTerminologyService(disabledProperties());
        service.reload(List.of(new TerminologyRecord(1, "global", null, "术语", "[\"口语\"]")));

        assertFalse(service.isEnabled());
        TerminologyResult result = service.replaceGlobal("口语问题");

        assertFalse(result.changed());
        assertEquals("口语问题", result.text());
        assertEquals("口语问题", service.replaceForKnowledgeBase("口语问题", "kb-1").text());
    }

    @Test
    void enabled_appliesGlobalRules() {
        DefaultTerminologyService service = new DefaultTerminologyService(enabledProperties());
        service.reload(List.of(new TerminologyRecord(1, "global", null, "术语", "[\"口语\"]")));

        assertTrue(service.isEnabled());
        TerminologyResult result = service.replaceGlobal("口语问题");

        assertTrue(result.changed());
        assertEquals("术语问题", result.text());
        assertEquals("global", result.applied().get(0).scope());
    }

    @Test
    void enabled_kbRulesOnlyApplyToMatchingKb() {
        DefaultTerminologyService service = new DefaultTerminologyService(enabledProperties());
        service.reload(List.of(new TerminologyRecord(2, "single_kb", "kb-1", "保证金监控", "[\"保证金系数\"]")));

        TerminologyResult hit = service.replaceForKnowledgeBase("保证金系数要求", "kb-1");
        TerminologyResult miss = service.replaceForKnowledgeBase("保证金系数要求", "kb-2");

        assertTrue(hit.changed());
        assertEquals("保证金监控要求", hit.text());
        assertFalse(miss.changed());
        // KB 口语不属于全局规则
        assertFalse(service.replaceGlobal("保证金系数要求").changed());
    }

    @Test
    void reload_swapsSnapshotAtomically() {
        DefaultTerminologyService service = new DefaultTerminologyService(enabledProperties());
        service.reload(List.of(new TerminologyRecord(1, "global", null, "术语", "[\"口语\"]")));
        assertTrue(service.replaceGlobal("口语问题").changed());

        service.reload(List.of());

        assertFalse(service.replaceGlobal("口语问题").changed());
    }

    @Test
    void enabled_withoutReload_returnsIdentity() {
        DefaultTerminologyService service = new DefaultTerminologyService(enabledProperties());

        assertFalse(service.replaceGlobal("口语问题").changed());
        assertFalse(service.status().loaded());
    }

    @Test
    void status_exposesSuccessfulLoadAndLatestFailure() {
        DefaultTerminologyService service = new DefaultTerminologyService(enabledProperties());
        service.reload(List.of(
                new TerminologyRecord(1, "global", null, "术语", "[\"口语\"]"),
                new TerminologyRecord(2, "single_kb", "kb-1", "保证金监控", "[\"保证金系数\"]")
        ));

        assertTrue(service.status().loaded());
        assertEquals(1, service.status().globalRuleCount());
        assertEquals(1, service.status().knowledgeBaseRuleCount());

        service.recordRefreshFailure(new RuntimeException("db down"));

        assertTrue(service.status().loaded());
        assertTrue(service.status().lastError().contains("db down"));
    }

    private static TerminologyReplacementProperties enabledProperties() {
        TerminologyReplacementProperties properties = new TerminologyReplacementProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static TerminologyReplacementProperties disabledProperties() {
        return new TerminologyReplacementProperties();
    }
}
