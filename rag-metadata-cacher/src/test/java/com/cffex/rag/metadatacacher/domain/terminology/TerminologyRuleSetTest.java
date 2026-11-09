package com.cffex.rag.metadatacacher.domain.terminology;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.terminology.TerminologyResult;

class TerminologyRuleSetTest {

    @Test
    void emptySet_returnsIdentity() {
        TerminologyResult result = TerminologyRuleSet.empty().apply("任意问题", "global", null);

        assertFalse(result.changed());
        assertEquals("任意问题", result.text());
        assertTrue(result.applied().isEmpty());
    }

    @Test
    void noMatch_returnsIdentity() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("国债期货", "中金所国债")));

        TerminologyResult result = ruleSet.apply("无关问题", "global", null);

        assertFalse(result.changed());
        assertEquals("无关问题", result.text());
        assertTrue(result.applied().isEmpty());
    }

    @Test
    void match_replacesColloquialWithTerm() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("国债期货", "中金所国债")));

        TerminologyResult result = ruleSet.apply("中金所国债的保证金怎么算", "global", null);

        assertTrue(result.changed());
        assertEquals("国债期货的保证金怎么算", result.text());
        assertEquals(1, result.applied().size());
        assertEquals("中金所国债", result.applied().get(0).colloquial());
        assertEquals("国债期货", result.applied().get(0).term());
        assertEquals("global", result.applied().get(0).scope());
    }

    @Test
    void longestColloquialWinsAtSamePosition() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(
                new TerminologyRule("A", "国债"),
                new TerminologyRule("B", "国债期货")
        ));

        TerminologyResult result = ruleSet.apply("国债期货市场", "global", null);

        assertEquals("B市场", result.text());
        assertEquals(1, result.applied().size());
    }

    @Test
    void latinColloquialMatchesCaseInsensitively() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("ETF", "etf基金")));

        TerminologyResult result = ruleSet.apply("买点 ETF基金 吧", "global", null);

        assertTrue(result.changed());
        assertEquals("买点 ETF 吧", result.text());
    }

    @Test
    void multipleOccurrencesAllReplaced() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("X", "abc")));

        TerminologyResult result = ruleSet.apply("abc 1 abc 2", "global", null);

        assertEquals("X 1 X 2", result.text());
        assertEquals(2, result.applied().size());
    }

    @Test
    void noChainedReplacementOnProducedText() {
        // 替换产物 "BC" 包含另一口语 "C"，单遍扫描不得对产物再次替换
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(
                new TerminologyRule("BC", "AB"),
                new TerminologyRule("X", "C")
        ));

        TerminologyResult result = ruleSet.apply("AB", "global", null);

        assertEquals("BC", result.text());
    }

    @Test
    void regexMetacharactersMatchedLiterally() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("美元", "usd$")));

        TerminologyResult result = ruleSet.apply("支付 usd$ 即可", "global", null);

        assertEquals("支付 美元 即可", result.text());
    }

    @Test
    void dollarInTermWrittenLiterally() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("USD$", "美元")));

        TerminologyResult result = ruleSet.apply("美元结算", "global", null);

        assertEquals("USD$结算", result.text());
    }

    @Test
    void ambiguousColloquial_takesLexicographicallySmallestTerm() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(
                new TerminologyRule("术语B", "口语"),
                new TerminologyRule("术语A", "口语")
        ));

        TerminologyResult result = ruleSet.apply("口语问题", "global", null);

        assertEquals("术语A问题", result.text());
        assertEquals(1, ruleSet.rules().size());
    }

    @Test
    void appliedCarriesKbScope() {
        TerminologyRuleSet ruleSet = TerminologyRuleSet.of(List.of(new TerminologyRule("保证金监控", "保证金系数")));

        TerminologyResult result = ruleSet.apply("保证金系数是多少", "single_kb", "kb-1");

        assertEquals("保证金监控是多少", result.text());
        assertEquals("single_kb", result.applied().get(0).scope());
        assertEquals("kb-1", result.applied().get(0).knowledgeBaseId());
    }
}
