package com.musicbridge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.2：GET /ready 的数据库就绪语义、超时、免认证与本机访问限制。
 */
class ReadinessApiTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ObjectMapper objectMapper;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SecurityProperties properties = new SecurityProperties();
        properties.setLocalUserId("local-user");
        sessionService = new SessionService(new EmptySessionRepository(), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readyReturnsUpWithoutTokenWhenDatabaseResponds() throws Exception {
        DataSource dataSource = healthyDataSource();

        mockMvc(dataSource).perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").value("2026-01-01T00:00:00Z"))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("MySQL"))))
                .andExpect(content().string(not(containsString("3306"))));
    }

    @Test
    void readyReturnsServiceUnavailableWhenConnectionCannotBeObtained() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        mockMvc(dataSource).perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(content().string(not(containsString("connection refused"))))
                .andExpect(content().string(not(containsString("SQLException"))));
    }

    @Test
    void readyReturnsServiceUnavailableWhenConnectionIsInvalid() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        mockMvc(dataSource).perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void readyReturnsServiceUnavailableWhenProbeExceedsTimeout() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(3_000);
            throw new SQLException("probe timed out");
        });

        long startedAt = System.nanoTime();
        mockMvc(dataSource).perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMs < 3_000, "探测必须在 2 秒上限内返回，实际耗时 " + elapsedMs + "ms");
    }

    @Test
    void nonLoopbackRequestIsForbidden() throws Exception {
        mockMvc(healthyDataSource()).perform(get("/ready").with(remoteAddr("192.168.1.10")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("仅允许本机访问"));
    }

    private DataSource healthyDataSource() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        return dataSource;
    }

    private MockMvc mockMvc(DataSource dataSource) {
        return MockMvcBuilders
                .standaloneSetup(new ReadinessController(dataSource, Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new AuthFilter(sessionService, objectMapper))
                .build();
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
