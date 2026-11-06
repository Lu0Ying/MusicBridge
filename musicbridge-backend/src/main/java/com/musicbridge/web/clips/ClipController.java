package com.musicbridge.web.clips;

import com.musicbridge.clips.ClipResponse;
import com.musicbridge.clips.ClipService;
import com.musicbridge.security.AuthContext;
import com.musicbridge.web.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * `/clips` 控制器：只透传认证上下文中的 userId，归属校验全部在服务层完成。
 */
@RestController
public class ClipController implements ClipsApi {

    private final ClipService clipService;

    public ClipController(ClipService clipService) {
        this.clipService = clipService;
    }

    @Override
    @PostMapping(path = "/api/v1/clips", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClipResponse> uploadClip(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext authContext,
            @RequestPart("file") MultipartFile file,
            @RequestPart("consentId") String consentId,
            @RequestPart("format") String format,
            @RequestPart(value = "durationMs", required = false) String durationMs,
            @RequestPart(value = "fileName", required = false) String fileName) {
        ClipResponse response = clipService.create(authContext.userId(), file, consentId, format,
                parseDurationHint(durationMs), fileName);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    @GetMapping(path = "/api/v1/clips/{clipId}")
    public ResponseEntity<ClipResponse> getClip(
            @RequestAttribute(AuthContext.REQUEST_ATTRIBUTE) AuthContext authContext,
            @PathVariable String clipId) {
        return ResponseEntity.ok(clipService.get(authContext.userId(), clipId));
    }

    /** 客户端时长提示为可选纯文本字段；非整数按契约按 400 处理，不静默丢弃 */
    private Long parseDurationHint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("durationMs 必须为整数");
        }
    }
}
