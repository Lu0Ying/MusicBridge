package com.musicbridge.web.clips;

import com.musicbridge.clips.ClipResponse;
import com.musicbridge.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * `/clips` 接口契约（Phase 1.2），由 {@link ClipController} 实现。
 *
 * <p>冻结口径见 {@code research/musicbridge-phase1/CLIPS_CONTRACT.md}：25 MB 实际字节、
 * 5 分钟实测时长、MP3/M4A/WAV/FLAC/OGG、授权双重校验、跨用户 404、过期片段 404。</p>
 */
@Tag(name = "片段管理", description = "音频片段上传与查询")
public interface ClipsApi {

    @Operation(summary = "上传音频文件并创建片段",
            description = "multipart/form-data；服务端校验文件实际字节数、魔数/容器特征与实测时长，"
                    + "并绑定本人的 RAW_AUDIO 授权（retentionDays ≥ 1）。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "字段校验失败或音频时长越界"),
            @ApiResponse(responseCode = "401", description = "缺少或无效的会话令牌"),
            @ApiResponse(responseCode = "403", description = "授权已撤销/过期、scope 非 RAW_AUDIO 或 retentionDays 为 0"),
            @ApiResponse(responseCode = "404", description = "授权不存在或不属于当前用户"),
            @ApiResponse(responseCode = "413", description = "文件实际字节超过 25 MB"),
            @ApiResponse(responseCode = "415", description = "格式/MIME/扩展名不一致、魔数不匹配或无法解析时长")
    })
    ResponseEntity<ClipResponse> uploadClip(
            @Parameter(hidden = true) AuthContext authContext,
            @Parameter(description = "音频文件，最大 25 MB") MultipartFile file,
            @Parameter(description = "本人有效授权 ID") String consentId,
            @Parameter(description = "MP3/M4A/WAV/FLAC/OGG") String format,
            @Parameter(description = "客户端时长提示（毫秒，可选整数），服务端以实测值为准") String durationMs,
            @Parameter(description = "展示用原始文件名，不参与落盘路径") String fileName);

    @Operation(summary = "查询本人片段", description = "跨用户、已过期或已清理的片段统一返回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "clipId 不是 UUID"),
            @ApiResponse(responseCode = "401", description = "缺少或无效的会话令牌"),
            @ApiResponse(responseCode = "404", description = "片段不存在、不属于当前用户或已过期")
    })
    ResponseEntity<ClipResponse> getClip(AuthContext authContext,
                                         @Parameter(description = "片段 UUID") String clipId);
}
