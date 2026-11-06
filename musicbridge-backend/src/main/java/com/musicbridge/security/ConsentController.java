package com.musicbridge.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 最小上传授权接口。用户身份只来自认证上下文，不接收客户端 userId。
 */
@RestController
@RequestMapping("/api/v1/consents")
@Tag(name = "上传授权", description = "授权创建、查询与撤销")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "为当前用户创建上传授权")
    public ConsentService.ConsentDTO create(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context,
            @Valid @RequestBody ConsentService.ConsentCreateRequest request) {
        return consentService.create(context.userId(), request);
    }

    @GetMapping
    @Operation(summary = "查询当前用户的上传授权列表")
    public List<ConsentService.ConsentDTO> list(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context) {
        return consentService.list(context.userId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询当前用户的上传授权")
    public ConsentService.ConsentDTO get(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context,
            @PathVariable String id) {
        return consentService.get(context.userId(), id);
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "撤销上传授权")
    public ConsentService.ConsentDTO revoke(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context,
            @PathVariable String id) {
        return consentService.revoke(context.userId(), id);
    }

    @PostMapping("/{id}/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "校验授权是否仍可用于上传（上传链路前置检查）")
    public void verify(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext context,
            @PathVariable String id) {
        consentService.requireActive(context.userId(), id);
    }
}
