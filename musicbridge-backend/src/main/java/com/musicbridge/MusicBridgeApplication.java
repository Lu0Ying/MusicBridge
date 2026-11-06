package com.musicbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MusicBridge Phase 0 后端启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MusicBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MusicBridgeApplication.class, args);
    }
}
