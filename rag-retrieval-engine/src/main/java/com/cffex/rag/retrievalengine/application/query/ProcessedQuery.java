package com.cffex.rag.retrievalengine.application.query;

import java.util.Objects;

/**
 * 查询预处理后的内部查询对象。
 *
 * <p>把 Spring AI Query 约束在预处理器内部，执行主链统一使用本模块自己的
 * 轻量查询对象。
 */
public record ProcessedQuery(String text) {

    public ProcessedQuery {
        Objects.requireNonNull(text, "text must not be null");
    }
}
