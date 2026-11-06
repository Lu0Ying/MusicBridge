package com.musicbridge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.musicbridge.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证、授权撤销、跨用户隔离与 CORS 的接口级测试。
 * 使用内存存储与固定时钟，不依赖 MySQL，也不访问外部网络。
 */
class SecurityApiTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private SessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setLocalUserId("local-user");
        properties.setLocalUsername("local");
        properties.setLocalPassword("test-password");
        properties.getCors().setAllowedOrigins(List.of(ALLOWED_ORIGIN, "http://127.0.0.1:5173"));

        sessionService = new SessionService(new InMemorySessionRepository(), properties, clock);
        ConsentService consentService = new ConsentService(new InMemoryConsentRepository(), clock);

        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(sessionService),
                        new ConsentController(consentService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CorsConfig().corsFilter(properties),
                        new AuthFilter(sessionService, objectMapper))
                .build();
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void authenticatedRequestUsesServerSideUserId() throws Exception {
        String token = login("local", "test-password");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        // 客户端伪造的 userId 不应生效
                        .param("userId", "attacker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("local-user"));
    }

    @Test
    void missingOrInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesSession() throws Exception {
        String token = login("local", "test-password");
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossUserConsentAccessIsIsolated() throws Exception {
        String ownerToken = login("local", "test-password");
        String otherToken = sessionService.issueForUser("user-b").token();
        String consentId = createConsent(ownerToken);

        mockMvc.perform(get("/api/v1/consents/{id}", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/consents/{id}/revoke", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/consents").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/consents/{id}", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consentId));
    }

    @Test
    void revokedConsentCannotBeUsedForUpload() throws Exception {
        String token = login("local", "test-password");
        String consentId = createConsent(token);

        mockMvc.perform(post("/api/v1/consents/{id}/verify", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/consents/{id}/revoke", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").exists());
        mockMvc.perform(post("/api/v1/consents/{id}/verify", consentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void allowedOriginReceivesCorsHeader() throws Exception {
        String token = login("local", "test-password");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void disallowedOriginIsRejected() throws Exception {
        String token = login("local", "test-password");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightFromAllowedOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String createConsent(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"RAW_AUDIO\",\"purpose\":\"recognition-upload\","
                                + "\"retentionDays\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static final class InMemorySessionRepository implements SessionRepository {

        private final Map<String, SessionRecord> byHash = new LinkedHashMap<>();
        private final Map<String, SessionRecord> byId = new LinkedHashMap<>();

        @Override
        public void save(SessionRecord session) {
            byHash.put(session.tokenHash(), session);
            byId.put(session.id(), session);
        }

        @Override
        public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
            SessionRecord session = byHash.get(tokenHash);
            if (session == null || session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(session);
        }

        @Override
        public boolean revoke(String sessionId, Instant now) {
            SessionRecord session = byId.get(sessionId);
            if (session == null || session.revokedAt() != null) {
                return false;
            }
            SessionRecord revoked = new SessionRecord(session.id(), session.userId(), session.tokenHash(),
                    session.createdAt(), session.expiresAt(), now);
            byId.put(sessionId, revoked);
            byHash.put(session.tokenHash(), revoked);
            return true;
        }
    }

    private static final class InMemoryConsentRepository implements ConsentRepository {

        private final Map<String, UploadConsentRecord> byId = new LinkedHashMap<>();

        @Override
        public void insert(UploadConsentRecord consent) {
            byId.put(consent.id(), consent);
        }

        @Override
        public Optional<UploadConsentRecord> findByIdAndUserId(String id, String userId) {
            UploadConsentRecord consent = byId.get(id);
            return consent != null && consent.userId().equals(userId) ? Optional.of(consent) : Optional.empty();
        }

        @Override
        public List<UploadConsentRecord> findByUserId(String userId) {
            return byId.values().stream().filter(consent -> consent.userId().equals(userId)).toList();
        }

        @Override
        public boolean revoke(String id, String userId, Instant now) {
            UploadConsentRecord consent = byId.get(id);
            if (consent == null || !consent.userId().equals(userId) || consent.revokedAt() != null) {
                return false;
            }
            byId.put(id, new UploadConsentRecord(consent.id(), consent.userId(), consent.scope(),
                    consent.purpose(), consent.grantedAt(), now, consent.expiresAt(), consent.retentionDays()));
            return true;
        }
    }
}
