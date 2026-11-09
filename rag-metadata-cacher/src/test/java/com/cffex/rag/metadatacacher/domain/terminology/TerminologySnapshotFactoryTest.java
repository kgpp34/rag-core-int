package com.cffex.rag.metadatacacher.domain.terminology;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TerminologySnapshotFactoryTest {

    @Test
    void globalRecord_parsedIntoGlobalRuleSet() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(1, "global", null, "国债期货", "[\"中金所国债\",\"国债合约\"]")
        ));

        assertEquals(1, snapshot.version());
        assertEquals(2, snapshot.global().rules().size());
        assertTrue(snapshot.byKbId().isEmpty());
    }

    @Test
    void singleKbRecord_parsedIntoKbRuleSet() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(2, "single_kb", "kb-1", "保证金监控", "[\"保证金系数\"]")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
        assertEquals(1, snapshot.byKbId().size());
        assertEquals(1, snapshot.byKbId().get("kb-1").rules().size());
    }

    @Test
    void singleKbWithoutKbId_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(3, "single_kb", null, "术语", "[\"口语\"]")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
        assertTrue(snapshot.byKbId().isEmpty());
    }

    @Test
    void unknownScope_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(4, "per_user", "kb-1", "术语", "[\"口语\"]")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
        assertTrue(snapshot.byKbId().isEmpty());
    }

    @Test
    void malformedDescJson_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(5, "global", null, "术语", "not-json")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
    }

    @Test
    void nonArrayDesc_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(6, "global", null, "术语", "{\"k\":\"v\"}")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
    }

    @Test
    void nonTextElements_ignoredWhileTextKept() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(7, "global", null, "术语", "[\"口语\", 42, null, \"\"]")
        ));

        assertEquals(1, snapshot.global().rules().size());
        assertEquals("口语", snapshot.global().rules().get(0).colloquial());
    }

    @Test
    void blankTerm_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(8, "global", null, "  ", "[\"口语\"]")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
    }

    @Test
    void blankDescJson_skipped() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, List.of(
                new TerminologyRecord(9, "global", null, "术语", " ")
        ));

        assertTrue(snapshot.global().rules().isEmpty());
    }

    @Test
    void nullRecords_yieldsEmptySnapshot() {
        TerminologySnapshot snapshot = TerminologySnapshotFactory.create(1, null);

        assertTrue(snapshot.global().rules().isEmpty());
        assertTrue(snapshot.byKbId().isEmpty());
    }
}
