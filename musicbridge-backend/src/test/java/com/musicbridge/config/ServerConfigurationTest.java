package com.musicbridge.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2：默认监听端口与本机绑定的一致性校验。
 */
class ServerConfigurationTest {

    @Test
    void openApiServerUsesLoopbackAndDefaultPort() {
        OpenAPI openApi = new MusicBridgeConfiguration(new MockEnvironment()).musicBridgeOpenApi();

        assertEquals(1, openApi.getServers().size());
        assertEquals("http://127.0.0.1:18080", openApi.getServers().get(0).getUrl());
    }

    @Test
    void portOverrideIsReflectedInOpenApiServer() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "19000");
        OpenAPI openApi = new MusicBridgeConfiguration(environment).musicBridgeOpenApi();

        assertEquals("http://127.0.0.1:19000", openApi.getServers().get(0).getUrl());
    }

    @Test
    void applicationYamlDefaultsToLoopback18080() throws IOException {
        String yaml;
        try (var input = new ClassPathResource("application.yml").getInputStream()) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(yaml.contains("port: ${MB_SERVER_PORT:18080}"), "默认端口必须是 18080");
        assertTrue(yaml.contains("address: 127.0.0.1"), "必须默认绑定 127.0.0.1");
        assertFalse(Pattern.compile("\\b8080\\b").matcher(yaml).find(), "不得残留 8080 默认值");
        assertFalse(yaml.contains("0.0.0.0"), "不得绑定 0.0.0.0");
    }
}
