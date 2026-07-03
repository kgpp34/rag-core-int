package com.cffex.rag.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceLoggingFilterTest {

    private final TraceLoggingFilter filter = new TraceLoggingFilter();

    @Test
    void doFilter_usesTraceIdFromRequestHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rag/answer");
        request.addHeader("X-Trace-Id", "trace-from-caller");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                traceIdInChain.set(MDC.get(TraceLoggingFilter.TRACE_ID_KEY));

        filter.doFilter(request, response, chain);

        assertThat(traceIdInChain).hasValue("trace-from-caller");
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-from-caller");
        assertThat(MDC.get(TraceLoggingFilter.TRACE_ID_KEY)).isNull();
    }

    @Test
    void doFilter_doesNotGenerateTraceIdWhenRequestHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rag/answer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>("stale");
        FilterChain chain = (servletRequest, servletResponse) ->
                traceIdInChain.set(MDC.get(TraceLoggingFilter.TRACE_ID_KEY));

        filter.doFilter(request, response, chain);

        assertThat(traceIdInChain).hasValue(null);
        assertThat(response.getHeader("X-Trace-Id")).isNull();
        assertThat(MDC.get(TraceLoggingFilter.TRACE_ID_KEY)).isNull();
    }
}
