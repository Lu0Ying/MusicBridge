package com.musicbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地临时片段存储配置。默认放在系统临时目录下的独立子目录。
 */
@ConfigurationProperties(prefix = "musicbridge.fragments")
public class FragmentProperties {

    private Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "musicbridge-fragments");

    public Path getTempRoot() {
        return tempRoot;
    }

    public void setTempRoot(Path tempRoot) {
        this.tempRoot = tempRoot;
    }
}
