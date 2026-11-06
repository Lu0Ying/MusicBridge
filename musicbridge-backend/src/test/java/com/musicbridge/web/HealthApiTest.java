package com.musicbridge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.musicbridge.security.AuthFilter;
import com.musicbridge.security.SecurityProperties;
import com.musicbridge.security.SessionRecord;
import com.musicbridge.security.SessionRepository;
import com.musicbridge.security.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.2：GET /health 的免认证与本机访问限制。
 */
class HealthApiTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SecurityProperties properties = new SecurityProperties();
        properties.setLocalUserId("local-user");
        SessionService sessionService = new SessionService(new EmptySessionRepository(), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new AuthFilter(sessionService, objectMapper))
                .build();
    }

    @Test
    void loopbackRequestNeedsNoTokenAndReturnsMinimalStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").value("2026-01-01T00:00:00Z"))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("version"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void ipv6LoopbackIsAccepted() throws Exception {
        mockMvc.perform(get("/health").with(remoteAddr("::1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void nonLoopbackRequestIsForbidden() throws Exception {
        mockMvc.perform(get("/health").with(remoteAddr("192.168.1.10")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("仅允许本机访问"));
    }

    private RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static final class EmptySessionRepository implements SessionRepository {

        @Override
        public void save(SessionRecord session) {
        }

        @Override
        public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
            return Optional.empty();
        }

        @Override
        public boolean revoke(String sessionId, Instant now) {
            return false;
        }
    }
}
