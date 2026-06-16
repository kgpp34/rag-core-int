package com.cffex.rag.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路日志过滤器。
 *
 * <p>负责为每个请求补齐 traceId，并在请求进入和结束时记录统一访问日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    private static final Logger log = LoggerFactory.getLogger(TraceLoggingFilter.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = Optional.ofNullable(request.getHeader(TRACE_ID_HEADER))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        long start = System.currentTimeMillis();

        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        log.info("收到请求，请求方式={}，路径={}，查询串={}，traceId={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("请求处理完成，请求方式={}，路径={}，状态码={}，耗时={}ms，traceId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMs,
                    traceId);
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
