package com.cffex.rag.metadatacacher.domain.terminology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cffex.rag.common.domain.terminology.TerminologyReplacement;
import com.cffex.rag.common.domain.terminology.TerminologyResult;

/**
 * 一组预编译的术语替换规则（全局规则集或单个知识库的规则集）。
 *
 * <p>构建期完成：同口语（忽略大小写）歧义消解、按口语长度降序排序、合并为单条交替正则。
 * {@link #apply} 为单遍扫描：沿原文消费不重叠区间，替换产物不会被再次扫描，因此阶段内不存在链式替换。
 */
public record TerminologyRuleSet(
        List<TerminologyRule> rules,
        Pattern pattern,
        Map<String, String> termByLowerColloquial
) {
    private static final Logger log = LoggerFactory.getLogger(TerminologyRuleSet.class);

    public TerminologyRuleSet {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        termByLowerColloquial = Map.copyOf(Objects.requireNonNull(termByLowerColloquial, "termByLowerColloquial must not be null"));
    }

    public static TerminologyRuleSet empty() {
        return new TerminologyRuleSet(List.of(), null, Map.of());
    }

    /**
     * 构建规则集：歧义消解 → 最长优先排序 → 预编译正则。
     *
     * <p>同一口语（忽略大小写）映射到不同术语时，取术语字典序最小者并告警，保证确定性。
     */
    public static TerminologyRuleSet of(List<TerminologyRule> inputRules) {
        if (inputRules == null || inputRules.isEmpty()) {
            return empty();
        }
        Map<String, String> termByLowerColloquial = new LinkedHashMap<>();
        Map<String, String> colloquialByLowerColloquial = new LinkedHashMap<>();
        for (TerminologyRule rule : inputRules) {
            String lowerColloquial = rule.colloquial().toLowerCase(Locale.ROOT);
            String existingTerm = termByLowerColloquial.get(lowerColloquial);
            if (existingTerm == null) {
                termByLowerColloquial.put(lowerColloquial, rule.term());
                colloquialByLowerColloquial.put(lowerColloquial, rule.colloquial());
                continue;
            }
            if (existingTerm.equals(rule.term())) {
                continue;
            }
            String winnerTerm = existingTerm.compareTo(rule.term()) <= 0 ? existingTerm : rule.term();
            log.warn("术语规则口语映射存在歧义，colloquial={}，候选术语={}，取字典序最小者={}",
                    rule.colloquial(), List.of(existingTerm, rule.term()), winnerTerm);
            termByLowerColloquial.put(lowerColloquial, winnerTerm);
        }

        List<TerminologyRule> sortedRules = colloquialByLowerColloquial.entrySet().stream()
                .map(entry -> new TerminologyRule(termByLowerColloquial.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator
                        .comparingInt((TerminologyRule rule) -> rule.colloquial().length()).reversed()
                        .thenComparing(TerminologyRule::colloquial))
                .toList();
        if (sortedRules.isEmpty()) {
            return empty();
        }
        String alternation = sortedRules.stream()
                .map(rule -> Pattern.quote(rule.colloquial()))
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        Pattern pattern = Pattern.compile(alternation, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new TerminologyRuleSet(sortedRules, pattern, termByLowerColloquial);
    }

    /**
     * 对文本做单遍替换。未命中任何规则时返回恒等结果。
     */
    public TerminologyResult apply(String text, String scope, String knowledgeBaseId) {
        Objects.requireNonNull(text, "text must not be null");
        if (pattern == null || rules.isEmpty() || text.isEmpty()) {
            return TerminologyResult.identity(text);
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder replaced = new StringBuilder(text.length());
        List<TerminologyReplacement> applied = new ArrayList<>();
        while (matcher.find()) {
            String term = termByLowerColloquial.get(matcher.group().toLowerCase(Locale.ROOT));
            if (term == null) {
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            applied.add(new TerminologyReplacement(matcher.group(), term, scope, knowledgeBaseId));
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(term));
        }
        if (applied.isEmpty()) {
            return TerminologyResult.identity(text);
        }
        matcher.appendTail(replaced);
        return new TerminologyResult(replaced.toString(), true, applied);
    }
}
