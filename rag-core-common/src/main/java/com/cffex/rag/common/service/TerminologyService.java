package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.terminology.TerminologyResult;
import com.cffex.rag.common.domain.terminology.TerminologyStatus;

/**
 * 检索术语替换端口：把 query 中的口语替换为专业术语（数据来自主库 t_kb_terminology）。
 *
 * <p>替换直接作用于检索阶段（embedding + 全文检索关键词）。回答模型应保留用户原文，
 * 同时携带命中的标准术语和检索问题变体，避免丢失用户语义或专业术语。
 * 级联语义：先 {@link #replaceGlobal(String)} 做全局替换，再对每个知识库在全局结果之上
 * 调用 {@link #replaceForKnowledgeBase(String, String)} 叠加 KB 专属规则。
 *
 * <p>特性关闭或无规则命中时返回 {@link TerminologyResult#identity(String)}，调用方无需判空。
 * {@link #DISABLED} 为关闭态缺省实现，供未装配实现方的场景（如测试构造器）使用。
 */
public interface TerminologyService {

    boolean isEnabled();

    default TerminologyResult replaceGlobal(String query) {
        return TerminologyResult.identity(query);
    }

    default TerminologyResult replaceForKnowledgeBase(String query, String knowledgeBaseId) {
        return TerminologyResult.identity(query);
    }

    default TerminologyStatus status() {
        return TerminologyStatus.disabled();
    }

    TerminologyService DISABLED = new TerminologyService() {
        @Override
        public boolean isEnabled() {
            return false;
        }
    };
}
