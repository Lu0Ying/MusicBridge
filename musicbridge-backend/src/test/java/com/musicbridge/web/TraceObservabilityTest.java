package com.musicbridge.web;

import com.musicbridge.common.RequestContext;
import com.musicbridge.common.TraceContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.observability.InMemoryProviderCallLogRecorder;
import com.musicbridge.provider.observability.ProviderCallLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * trace 链路测试：HTTP 头 → MDC → RequestContext → Provider 调用日志查询。
 */
class TraceObservabilityTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilters(new TraceFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void echoesCallerTraceIdAndExposesItToTheRequest() throws Exception {
        mockMvc.perform(get("/echo").header(TraceContext.HEADER, "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER, "trace-abc-123"))
                .andExpect(jsonPath("$.traceId").value("trace-abc-123"))
                .andExpect(jsonPath("$.mdcTraceId").value("trace-abc-123"));
        assertNull(TraceContext.currentTraceId(), "请求结束后必须清理 MDC，避免线程复用串号");
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/echo"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceContext.HEADER))
                .andExpect(jsonPath("$.traceId").value(
                        org.hamcrest.Matchers.matchesPattern(
                                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void rejectsMalformedTraceIdWithoutEchoingIt() throws Exception {
        String malformed = "bad trace id with spaces";
        mockMvc.perform(get("/echo").header(TraceContext.HEADER, malformed))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER,
                        org.hamcrest.Matchers.not(malformed)))
                .andExpect(jsonPath("$.traceId").value(
                        org.hamcrest.Matchers.not(malformed)));
    }

    @Test
    void requestContextReusesCurrentTraceId() {
        TraceContext.start("trace-request-1");
        RequestContext ctx = RequestContext.create("user-1");
        assertEquals("trace-request-1", ctx.traceId());

        TraceContext.clear();
        RequestContext standalone = RequestContext.create("user-1");
        assertNotNull(standalone.traceId(), "无请求链路时仍应生成 traceId");
        assertFalse(standalone.traceId().isBlank());
    }

    @Test
    void callLogsCanBeQueriedByTraceId() {
        InMemoryProviderCallLogRecorder recorder = new InMemoryProviderCallLogRecorder();
        recorder.record(log("trace-request-1", "mock-metadata"));
        recorder.record(log("trace-other", "mock-lyrics"));
        recorder.record(log("trace-request-1", "mock-lyrics"));

        List<ProviderCallLog> matched = recorder.byTraceId("trace-request-1", 10);

        assertEquals(2, matched.size());
        assertTrue(matched.stream().allMatch(log -> "trace-request-1".equals(log.traceId())));
        assertTrue(recorder.byTraceId("missing", 10).isEmpty());
    }

    private ProviderCallLog log(String traceId, String provider) {
        return new ProviderCallLog(traceId, provider, ProviderCapability.METADATA, null, 1, null, null,
                null, 0, null, 0, null, null, null, BigDecimal.ZERO, false, Instant.now());
    }

    @RestController
    static class EchoController {

        @GetMapping("/echo")
        Map<String, String> echo(@RequestAttribute(value = TraceContext.REQUEST_ATTRIBUTE,
                required = false) String traceId) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("traceId", traceId);
            body.put("mdcTraceId", TraceContext.currentTraceId());
            return body;
        }
    }
}
