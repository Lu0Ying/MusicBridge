package com.musicbridge.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 基础设施 Bean：时钟、退避等待实现与 OpenAPI 文档信息。
 */
@Configuration
public class MusicBridgeConfiguration {

    private final Environment environment;

    public MusicBridgeConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 生产环境使用真实等待；测试可自行注入不等待实现 */
    @Bean
    public com.musicbridge.provider.manager.Sleeper sleeper() {
        return com.musicbridge.provider.manager.Sleeper.threadSleeper();
    }

    @Bean
    public OpenAPI musicBridgeOpenApi() {
        // 文档中的服务地址与实际监听地址/端口保持一致，便于桌面端直接复用
        String address = environment.getProperty("server.address", "127.0.0.1");
        String port = environment.getProperty("server.port", "18080");
        return new OpenAPI().info(new Info()
                        .title("MusicBridge Backend API")
                        .version("0.1.0")
                        .description("MusicBridge Phase 0 后端工程底座：Provider 接口、路由降级、"
                                + "限流缓存、调用计量与核心数据模型。Mock Provider 仅用于开发与测试。"))
                .addServersItem(new Server()
                        .url("http://" + address + ":" + port)
                        .description("本机后端"));
    }
}
