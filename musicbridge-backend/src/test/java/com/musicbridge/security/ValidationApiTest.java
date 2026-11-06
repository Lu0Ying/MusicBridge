package com.musicbridge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.musicbridge.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-06：现有端点的输入校验（空值、长度、格式、边界、非法组合）与统一错误响应。
 */
class ValidationApiTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private SessionService sessionService;
    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setLocalUserId("local-user");
        properties.setLocalUsername("local");
        properties.setLocalPassword("test-password");
        properties.getCors().setAllowedOrigins(List.of());

        sessionService = new SessionService(new StubSessionRepository(), properties, clock);
        ConsentService consentService = new ConsentService(new StubConsentRepository(), clock,
                (userId, consentId) -> FragmentCleaner.CleanupResult.empty());

        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(sessionService),
                        validated(new ConsentController(consentService)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new AuthFilter(sessionService, objectMapper))
                .build();
        token = sessionService.issueForUser("local-user").token();
    }

    /**
     * standaloneSetup 不会自动应用方法级校验，手动加上与生产环境一致的 {@code @Validated} 代理，
     * 以便验证路径参数上的约束也会走统一错误响应。
     */
    private ConsentController validated(ConsentController target) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new MethodValidationInterceptor(validator.getValidator()));
        return (ConsentController) factory.getProxy();
    }

    @Test
    void rejectsBlankCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsOverlongCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", repeat("u", 65), "password", "ok"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("username")));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "local", "password", repeat("p", 257)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnsupportedScope() throws Exception {
        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("FULL_LIBRARY", "recognition", 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsBlankPurposeAndOverlongPurpose() throws Exception {
        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", " ", 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", repeat("p", 201), 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsRetentionDaysOutsideRange() throws Exception {
        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", "recognition", -1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", "recognition", 366, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsExpiresAtInThePast() throws Exception {
        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", "recognition", 1, "2025-12-31T23:59:59Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMalformedConsentId() throws Exception {
        mockMvc.perform(get("/api/v1/consents/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void acceptsBoundaryValues() throws Exception {
        mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody("RAW_AUDIO", repeat("p", 200), 365, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.retentionDays").value(365));
    }

    private String consentBody(String scope, String purpose, int retentionDays, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("purpose", purpose);
        body.put("retentionDays", retentionDays);
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt);
        }
        return json(body);
    }

    private String json(Map<String, ?> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String repeat(String value, int count) {
        return value.repeat(count);
    }

    // ---------------------------------------------------------- 最小内存实现

    private static final class StubSessionRepository implements SessionRepository {

        private final Map<String, SessionRecord> sessions = new LinkedHashMap<>();

        @Override
        public void save(SessionRecord session) {
            sessions.put(session.tokenHash(), session);
        }

        @Override
        public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
            return Optional.ofNullable(sessions.get(tokenHash))
                    .filter(session -> session.revokedAt() == null && session.expiresAt().isAfter(now));
        }

        @Override
        public boolean revoke(String sessionId, Instant now) {
            boolean changed = false;
            for (Map.Entry<String, SessionRecord> entry : sessions.entrySet()) {
                SessionRecord session = entry.getValue();
                if (session.id().equals(sessionId) && session.revokedAt() == null) {
                    entry.setValue(new SessionRecord(session.id(), session.userId(), session.tokenHash(),
                            session.createdAt(), session.expiresAt(), now));
                    changed = true;
                }
            }
            return changed;
        }
    }

    private static final class StubConsentRepository implements ConsentRepository {

        private final Map<String, UploadConsentRecord> records = new LinkedHashMap<>();

        @Override
        public void insert(UploadConsentRecord consent) {
            records.put(consent.id(), consent);
        }

        @Override
        public Optional<UploadConsentRecord> findByIdAndUserId(String id, String userId) {
            return Optional.ofNullable(records.get(id)).filter(record -> record.userId().equals(userId));
        }

        @Override
        public List<UploadConsentRecord> findByUserId(String userId) {
            return records.values().stream().filter(record -> record.userId().equals(userId)).toList();
        }

        @Override
        public boolean revoke(String id, String userId, Instant now) {
            UploadConsentRecord record = records.get(id);
            if (record == null || !record.userId().equals(userId) || record.revokedAt() != null) {
                return false;
            }
            records.put(id, new UploadConsentRecord(record.id(), record.userId(), record.scope(),
                    record.purpose(), record.grantedAt(), now, record.expiresAt(), record.retentionDays()));
            return true;
        }
    }
}
