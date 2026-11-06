package com.musicbridge.web.clips;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.musicbridge.clips.ClipResponse;
import com.musicbridge.clips.ClipService;
import com.musicbridge.clips.ClipStatus;
import com.musicbridge.security.AccessForbiddenException;
import com.musicbridge.security.AuthFilter;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.security.SecurityProperties;
import com.musicbridge.security.SessionRecord;
import com.musicbridge.security.SessionRepository;
import com.musicbridge.security.SessionService;
import com.musicbridge.web.ApiExceptionHandler;
import com.musicbridge.web.PayloadTooLargeException;
import com.musicbridge.web.UnsupportedMediaTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/clips` 接口层测试：认证、multipart 绑定、状态码映射与 userId 只来自认证上下文。
 */
class ClipApiTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONSENT_ID = "8c1b3a8d-5b6e-4e7a-9c2d-0f1a2b3c4d5e";
    private static final String CLIP_ID = "0d1e2f3a-4b5c-6d7e-8f90-1a2b3c4d5e6f";

    private ClipService clipService;
    private SessionService sessionService;
    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SecurityProperties properties = new SecurityProperties();
        properties.setLocalUserId("local-user");
        properties.setLocalUsername("local");
        properties.setLocalPassword("test-password");
        sessionService = new SessionService(new EmptySessionRepository(), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        token = sessionService.login("local", "test-password").token();
        clipService = mock(ClipService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ClipController(clipService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper),
                        new StringHttpMessageConverter(StandardCharsets.UTF_8))
                .addFilters(new AuthFilter(sessionService, objectMapper))
                .build();
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/v1/clips")
                        .file(filePart())
                        .part(textPart("consentId", CONSENT_ID))
                        .part(textPart("format", "WAV")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadUsesUserIdFromAuthenticationContext() throws Exception {
        when(clipService.create(any(), any(), any(), any(), any(), any())).thenReturn(response());

        mockMvc.perform(multipart("/api/v1/clips")
                        .file(filePart())
                        .part(textPart("consentId", CONSENT_ID))
                        .part(textPart("format", "WAV"))
                        .part(textPart("durationMs", "1000"))
                        .part(textPart("fileName", "a.wav"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clipId").value(CLIP_ID))
                .andExpect(jsonPath("$.format").value("WAV"))
                .andExpect(jsonPath("$.durationMs").value(1_000));

        verify(clipService).create(eq("local-user"), any(MultipartFile.class), eq(CONSENT_ID),
                eq("WAV"), eq(1_000L), eq("a.wav"));
    }

    @Test
    void missingRequiredPartReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/v1/clips")
                        .file(filePart())
                        .part(textPart("format", "WAV"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void payloadTooLargeMapsTo413() throws Exception {
        when(clipService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new PayloadTooLargeException("文件超过 25 MB 上限"));

        mockMvc.perform(uploadRequest())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void unsupportedMediaTypeMapsTo415() throws Exception {
        when(clipService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new UnsupportedMediaTypeException("文件实际格式与声明不一致"));

        mockMvc.perform(uploadRequest())
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void forbiddenConsentMapsTo403() throws Exception {
        when(clipService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AccessForbiddenException("上传授权已撤销"));

        mockMvc.perform(uploadRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getUsesUserIdFromAuthenticationContext() throws Exception {
        when(clipService.get("local-user", CLIP_ID)).thenReturn(response());

        mockMvc.perform(get("/api/v1/clips/{clipId}", CLIP_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clipId").value(CLIP_ID));

        verify(clipService).get("local-user", CLIP_ID);
    }

    @Test
    void getMissingClipMapsTo404() throws Exception {
        when(clipService.get("local-user", CLIP_ID))
                .thenThrow(new ResourceNotFoundException("片段不存在"));

        mockMvc.perform(get("/api/v1/clips/{clipId}", CLIP_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uploadRequest() {
        return multipart("/api/v1/clips")
                .file(filePart())
                .part(textPart("consentId", CONSENT_ID))
                .part(textPart("format", "WAV"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockMultipartFile filePart() {
        return new MockMultipartFile("file", "a.wav", "audio/wav", new byte[]{'R', 'I', 'F', 'F'});
    }

    private MockPart textPart(String name, String value) {
        MockPart part = new MockPart(name, value.getBytes(StandardCharsets.UTF_8));
        part.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        return part;
    }

    private ClipResponse response() {
        return new ClipResponse(UUID.fromString(CLIP_ID), UUID.fromString(CONSENT_ID), "WAV",
                "audio/wav", 8_000, 1_000, "a.wav", NOW, NOW.plusSeconds(30L * 86_400), 30,
                ClipStatus.STORED);
    }

    private static final class EmptySessionRepository implements SessionRepository {

        private final java.util.Map<String, SessionRecord> sessions = new java.util.HashMap<>();

        @Override
        public void save(SessionRecord session) {
            sessions.put(session.tokenHash(), session);
        }

        @Override
        public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
            SessionRecord session = sessions.get(tokenHash);
            if (session == null || session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(session);
        }

        @Override
        public boolean revoke(String sessionId, Instant now) {
            return false;
        }
    }
}
