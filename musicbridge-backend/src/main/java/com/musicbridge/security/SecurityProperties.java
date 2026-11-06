package com.musicbridge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机最小安全配置。
 *
 * <p>单机部署只维护一个本地账号，用户名/密码来自环境变量；密码不落库、不写日志。
 * 会话令牌只保存哈希，CORS 允许来源按环境显式配置，默认空表示拒绝所有跨源。
 */
@ConfigurationProperties(prefix = "musicbridge.security")
public class SecurityProperties {

    /** 本地账号对应的用户 ID，服务端会话与业务数据归属都以此为准 */
    private String localUserId = "local-user";

    private String localUsername = "local";

    /** 为空时登录功能整体不可用，避免误配置成空密码 */
    private String localPassword = "";

    private Duration sessionTtl = Duration.ofDays(7);

    private Cors cors = new Cors();

    public String getLocalUserId() {
        return localUserId;
    }

    public void setLocalUserId(String localUserId) {
        this.localUserId = localUserId;
    }

    public String getLocalUsername() {
        return localUsername;
    }

    public void setLocalUsername(String localUsername) {
        this.localUsername = localUsername;
    }

    public String getLocalPassword() {
        return localPassword;
    }

    public void setLocalPassword(String localPassword) {
        this.localPassword = localPassword;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public static class Cors {

        /** 允许的跨源来源；空集合表示默认拒绝所有跨源请求 */
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
