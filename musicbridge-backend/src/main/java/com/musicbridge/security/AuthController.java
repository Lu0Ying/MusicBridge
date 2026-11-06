package com.musicbridge.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 本机最小认证接口：登录换取会话令牌，当前用户信息只从认证上下文读取。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "本机会话登录、当前用户与退出")
public class AuthController {

    private final SessionService sessionService;

    public AuthController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    @Operation(summary = "本地账号登录并签发会话令牌")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        SessionToken session = sessionService.login(request.username(), request.password());
        return new LoginResponse(session.token(), session.expiresAt(), session.userId());
    }

    @GetMapping("/me")
    @Operation(summary = "查询当前会话归属的用户")
    public MeResponse me(@RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context) {
        return new MeResponse(context.userId(), context.sessionId(), context.expiresAt());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "撤销当前会话")
    public void logout(@RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context) {
        sessionService.revokeSession(context.sessionId());
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名长度不能超过 64")
            String username,
            @NotBlank(message = "密码不能为空") @Size(max = 256, message = "密码长度不能超过 256")
            String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, String userId) {
    }

    public record MeResponse(String userId, String sessionId, Instant expiresAt) {
    }
}
