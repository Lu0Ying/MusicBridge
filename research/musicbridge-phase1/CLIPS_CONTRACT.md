# `/clips` 接口契约（冻结版 · Phase 1.2）

> 状态：**契约已冻结，尚未实现**（本文件是唯一事实来源）
> 冻结日期：2026-11-07
> 适用范围：Phase 1.2 文件导入与片段管理
> 实现批次：契约检查通过后再落地，遵循本文件全部强制约束

## 一、已冻结参数

| 参数 | 取值 |
| --- | --- |
| 单文件大小上限 | **25 MB（26214400 字节）**，按文件实际字节数，不含 multipart 开销 |
| 单文件时长上限 | **5 分钟（300000 ms）**，按服务端探测的实际时长 |
| 允许格式 | `MP3` / `M4A` / `WAV` / `FLAC` / `OGG`（大小写不敏感，归一化为大写） |
| 独立 AAC | **不支持**（`.aac` 文件按 415 拒绝） |
| `retentionDays = 0` | **禁止上传**（403） |
| 过期清理 | **每日定时任务 + 后端启动补扫一次** |
| `DELETE /api/v1/clips/{clipId}` | **不提供 HTTP 接口**；服务层保留可复用的单片段删除能力 |

## 二、端点

| 方法 | 路径 | 认证 | 成功码 |
| --- | --- | --- | --- |
| POST | `/api/v1/clips` | Bearer | 201 |
| GET | `/api/v1/clips/{clipId}` | Bearer | 200 |

- 两个端点均**不在** AuthFilter 免认证白名单中，必须携带有效会话令牌。
- `/clips` 不做回环限制（它是桌面端正常业务端点；仅 `/health`、`/ready` 限制本机）。
- `userId` **只**来自认证上下文（`AuthContext`）；客户端在表单、查询参数或 JSON 中传入的 `userId` 一律忽略，不作为归属依据。
- `clipId` 为服务端生成的 UUID v4；路径参数非 UUID 格式返回 400 `VALIDATION_FAILED`。

## 三、POST `/api/v1/clips`

`Content-Type: multipart/form-data`

| 字段 | 类型 | 必填 | 校验规则 |
| --- | --- | --- | --- |
| `file` | 二进制 part | 是 | 非空；实际文件字节 ≤ 25 MB；魔数/容器特征必须与 `format` 一致；实测时长 ≤ 5 分钟 |
| `consentId` | string(UUID) | 是 | 本人、未撤销、未过期、`scope=RAW_AUDIO`、`retentionDays ≥ 1` |
| `format` | enum `MP3\|M4A\|WAV\|FLAC\|OGG` | 是 | 与文件魔数、part Content-Type、扩展名（若提供）三者一致 |
| `durationMs` | integer | 否 | 客户端提示值，`500 ≤ value ≤ 300000`；与实测时长偏差 > 2000 ms 时返回 400，服务端始终以实测值为准 |
| `fileName` | string | 否 | 仅用于展示，≤ 120 字符，禁止路径分隔符与控制字符；不参与落盘路径 |

### 3.1 格式与 MIME 映射

| format | 扩展名 | 允许的 part Content-Type | 魔数/容器特征（实现校验口径） |
| --- | --- | --- | --- |
| MP3 | `.mp3` | `audio/mpeg` | `ID3` 头，或同步帧 `0xFF` + 帧头合法（MPEG 1/2/2.5 Layer I–III） |
| M4A | `.m4a` | `audio/mp4`、`audio/x-m4a` | ISO BMFF：首 4 字节 size 后紧跟 `ftyp`，major brand `M4A ` / `mp42` / `isom` |
| WAV | `.wav` | `audio/wav`、`audio/x-wav`、`audio/wave` | `RIFF` + size + `WAVE`，且含 `fmt ` 子块 |
| FLAC | `.flac` | `audio/flac`、`audio/x-flac` | 首 4 字节 `fLaC`，随后 STREAMINFO 块 |
| OGG | `.ogg` | `audio/ogg`、`application/ogg` | `OggS` 捕获模式，且首个逻辑流为 Vorbis（`\x01vorbis`）或 Opus（`OpusHead`） |

- 仅凭扩展名或 MIME 不足以通过：三者不一致 → 415；文件内容不是对应音频容器 → 415。
- 独立 AAC（`ADTS` 同步字 `0xFFF` 且无 MP3 帧头特征）→ 415。

### 3.2 时长探测口径

- 服务端必须探测**实际音频时长**，不得直接采用客户端 `durationMs`。
- 实测时长 < 500 ms → 400 `VALIDATION_FAILED`（"音频过短"）。
- 实测时长 > 300000 ms → 400 `VALIDATION_FAILED`（"音频超过 5 分钟"）。
- 无法解析时长（容器损坏、空音频流）→ 415 `UNSUPPORTED_MEDIA_TYPE`。
- 响应中的 `durationMs` 为实测值。

### 3.3 音频探测能力（**尚未选型，需确认，不擅自加依赖**）

现有 `pom.xml` 无音频解析依赖（仅 web/validation/jdbc/flyway/mysql/caffeine/springdoc）。

| 方案 | 说明 | 代价 |
| --- | --- | --- |
| A. 手写头部解析 | 自行解析 MP3 帧头与 Xing/VBRI、M4A `mvhd`、FLAC `STREAMINFO`、OGG granule | 零新增依赖；代码量与边界用例多，需为 5 种容器各自补测试 |
| B. `org.jaudiotagger:jaudiotagger`（推荐候选） | 覆盖 MP3/FLAC/M4A/OGG，可直接读取时长与封装格式 | 新增一个第三方 jar（**约 1 MB 级，具体体积与传递依赖需实现前实测并报备**） |
| C. Apache Tika | 能力全但引入较大依赖树 | 体积与启动开销大，**不推荐** |

本轮不添加任何依赖；实现批次开始前需你确认 A 或 B（若选 B 需同时确认新增依赖清单与体积）。

### 3.4 上传流程（实现顺序，含竞态处理）

1. 认证：取 `AuthContext.userId`，缺失 → 401。
2. 授权前置校验：`ConsentService.requireActive(userId, consentId)`；scope 非 `RAW_AUDIO`、已撤销/过期 → 403；`retentionDays < 1` → 403。
3. 字节级校验：`MultipartFile.getSize()` ≤ 25 MB，否则 413（不依赖容器层 multipart 限制作为唯一防线）。
4. 内容校验：魔数/容器特征、MIME、扩展名一致性，实测时长。
5. 落盘前**再次**校验授权（防撤销竞态）：失效则直接 403，不写文件。
6. `TempFragmentService.beginUpload(userId, consentId, "<clipId>.<ext>")` → 写入 → `completeUpload`（成功/失败均需调用）。
7. 写盘完成后再校验一次授权；若已撤销，立即删除刚写入文件并返回 403。
8. 写 `clip` 元数据行（同一事务边界内只包含 DB 写入；文件删除失败不影响事务，交由清理任务补偿）。
9. 返回 201。

失败清理：任何一步失败都必须删除已写入的半成品文件（幂等删除），不得遗留无主文件。

### 3.5 请求示例

```bash
curl -X POST http://127.0.0.1:18080/api/v1/clips \
  -H "Authorization: Bearer <token>" \
  -F "file=@sample.mp3;type=audio/mpeg" \
  -F "consentId=8d2a5c1e-…" \
  -F "format=MP3" \
  -F "durationMs=12000" \
  -F "fileName=sample.mp3"
```

## 四、响应

### 4.1 201 / 200 响应体（`ClipResponse`）

```json
{
  "clipId": "3f1c9b7a-…",
  "consentId": "8d2a5c1e-…",
  "format": "MP3",
  "contentType": "audio/mpeg",
  "sizeBytes": 1048576,
  "durationMs": 12000,
  "fileName": "sample.mp3",
  "createdAt": "2026-11-07T03:30:00Z",
  "expiresAt": "2026-11-08T03:30:00Z",
  "retentionDays": 1,
  "status": "STORED"
}
```

| 字段 | 说明 |
| --- | --- |
| `clipId` | 服务端生成的 UUID |
| `consentId` | 绑定授权 ID |
| `format` / `contentType` | 归一化格式与落库 MIME |
| `sizeBytes` | 文件实际字节数 |
| `durationMs` | **服务端实测时长** |
| `fileName` | 展示用原始文件名（已清洗，不含路径） |
| `createdAt` / `expiresAt` | UTC 时间戳；`expiresAt = createdAt + retentionDays` |
| `retentionDays` | 来自授权记录，客户端不可覆盖 |
| `status` | 仅返回 `STORED`（过期片段按 404 处理，见下） |

响应**不含** `userId`、绝对存储路径、主机名或任何内部标识。落盘相对路径 `sha256(userId)/consentId/clipId.ext` 仅存于数据库。

### 4.2 状态定义与迁移时点

| 状态 | 含义 | 进入时点 |
| --- | --- | --- |
| `STORED` | 已落盘、元数据完整，可被读取/用于识曲 | 上传成功写入 `clip` 行时 |
| `EXPIRED` | 已过期，禁止读取与识曲，等待物理清理 | ① `now ≥ expiresAt` 时清理任务将行更新为 `EXPIRED`；② 读取/识曲侧按 `now < expiresAt` 即时判定，未落库也拒绝 |

- 过期判定必须"双保险"：**DB 状态 + 时间比较**。即使清理任务尚未跑、状态仍为 `STORED`，只要 `now ≥ expiresAt` 一律视为不可用。
- 已过期片段：`GET /api/v1/clips/{clipId}` → **404 `NOT_FOUND`**（与不存在不可区分）；识曲取用时同样 404。
- `EXPIRED` 不对外暴露（客户端只会看到 404）。

## 五、错误码完整表

统一错误结构（沿用现有 `ApiError`）：

```json
{ "code": "VALIDATION_FAILED", "message": "…", "providerId": null }
```

| HTTP | code | 触发条件 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段缺失/类型错误；`clipId` 非 UUID；`durationMs` 越界或与实测偏差 > 2s；实测时长 < 500 ms 或 > 5 分钟 |
| 400 | `INVALID_REQUEST` | multipart 请求无法解析、缺少 `file` part |
| 401 | `UNAUTHENTICATED` | 缺少或无效 Bearer 令牌 |
| 403 | `FORBIDDEN` | 授权已撤销/已过期；scope 非 `RAW_AUDIO`；`retentionDays = 0`；写入完成前授权失效 |
| 404 | `NOT_FOUND` | `clipId` 不存在、不属于当前用户、已过期或已清理；`consentId` 不存在或不属于当前用户 |
| 413 | `PAYLOAD_TOO_LARGE` | 文件实际字节 > 25 MB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 格式/MIME/扩展名不一致；魔数或容器不匹配；独立 AAC；无法解析音频时长 |

其他语义：

- 跨用户一律 404，**不泄露存在性**；`consentId` 同理。
- `INVALID_INPUT` **不用于 `/clips`**，保留给 Phase 1.3 `/recognitions` 的"输入类型与识别能力不匹配"。
- 客户端只依赖 `code` 与 HTTP 状态，不解析 `message` 文案。
- 413/415 需在实现批次给 `ApiExceptionHandler` 增加映射（`MaxUploadSizeExceededException` → 413，自定义 `UnsupportedMediaTypeException` → 415）。

## 六、授权（`UploadConsent`）关系

| scope | 是否可用于 `/clips` | 说明 |
| --- | --- | --- |
| `RAW_AUDIO` | 是 | 保存原始音频文件，本契约唯一接受的 scope |
| `AUDIO_FINGERPRINT` | 否（403） | 仅保存声纹/特征向量，不落原始音频；保留给后续阶段 |

- 上传前校验：授权属于当前用户、未撤销、未过期、scope 匹配、`retentionDays ≥ 1`。
- 写入中/写入后再次校验（第 3.4 节步骤 5、7），确保撤销后不产生可用片段。
- 授权撤销时由 `TempFragmentService.cleanupForConsent` 清理该用户该授权目录下所有**非在途**片段（A-07 既有能力），并跳过在途上传；在途上传完成后由第 7 步自己删除文件。
- 授权到期不触发物理删除，但会使对应片段在业务侧不可用（按 `expiresAt`/撤销状态判定）。

## 七、存储与临时片段（`TempFragmentService`）

- 路径：`<musicbridge.fragments.root>/<sha256(userId)>/<consentId>/<clipId>.<ext>`。
- 目录名与文件名沿用服务层 `SAFE_NAME` 规则，禁止 `..`、路径分隔符与符号链接穿越。
- 授权撤销清理、用户隔离、在途跳过逻辑**复用**现有 `TempFragmentService`，不在控制器内自实现清理。
- 进程重启不做全盘扫描；无主文件由清理任务按 `clip` 行差集处理（见第八节）。

## 八、过期清理策略

### 8.1 触发方式

- **每日定时任务**：默认每天 03:00（`musicbridge.clips.cleanup-cron="0 0 3 * * *"`，可配置）；使用 Spring 调度，不新增依赖。
- **启动补扫**：应用就绪后执行一次（`ApplicationReadyEvent` 或等价钩子），异步执行，失败只记录日志不阻塞启动。

### 8.2 处理范围与顺序

1. 查询 `expiresAt ≤ now` 且 `status = STORED` 的片段，分批处理（建议每批 ≤ 200 条）。
2. 单条处理：`UPDATE clip SET status = 'EXPIRED' WHERE id = ? AND status = 'STORED'`（以条件更新保证幂等/并发安全）→ 删除对应文件 → 若该目录已空则删除该**单层**目录。
3. 文件不存在视为成功；文件删除失败保留 `EXPIRED` 行，下次重试。
4. 只删除 `clip` 行明确指向的文件，**禁止递归清空用户目录**、禁止按目录通配删除。
5. 每条独立事务/独立失败域，单条失败不中断整批。

### 8.3 幂等与重试

- 重复执行不产生错误：条件更新影响 0 行时跳过删除以外的副作用；文件已不存在时直接成功。
- 清理任务必须可重入、可并发（多实例/定时重叠）——靠条件更新与 `Files.deleteIfExists` 保证。
- 清理日志只记录 `clipId` 与结果，不打印绝对路径与用户标识明文。

## 九、单片段删除（服务层能力，不暴露 HTTP）

- 能力定义：`ClipService.delete(userId, clipId)`。
- 语义：归属校验失败（非本人/不存在/已删除）视为"未删除"，返回 `false`，不抛业务异常、不泄露存在性。
- 顺序：先条件删除 DB 行（`DELETE ... WHERE id=? AND user_id=?`），成功后再删文件（`deleteIfExists`），最后尝试删除空的单层目录。
- 幂等：重复调用返回 `false`，不报错；文件不存在视为成功。
- 用途：授权撤销清理、运维脚本、后续可能的 HTTP 删除端点；本阶段不挂 HTTP。

## 十、OpenAPI 声明

实现批次交付的 OpenAPI 应包含：

- `POST /api/v1/clips`：`multipart/form-data`，`file`（`type: string, format: binary`）、`consentId`（`uuid`）、`format`（enum）、`durationMs`（integer，可空）、`fileName`（string，可空）；响应 `ClipResponse`；错误响应 400/401/403/404/413/415。
- `GET /api/v1/clips/{clipId}`：路径参数 `uuid`；响应 `ClipResponse`；错误 400/401/404。
- `ClipResponse` 与 `ClipStatus` schema 中字段描述与第四节一致；不包含内部路径与 `userId`。

本轮以**注解草案 + 静态 YAML 片段**形式声明契约，不实现控制器：

- 注解草案：`ClipsApi`（`@Tag` / `@Operation` / `@ApiResponses`）、`ClipResponse`（`@Schema`）、`ClipStatus`。
- 草案类未标注 `@RestController`，因此 `/v3/api-docs` **暂不包含** `/clips`；实现批次由 `ClipsController` 实现 `ClipsApi` 后自动纳入文档。

静态片段（供审查，最终以实现生成的文档为准）：

```yaml
paths:
  /api/v1/clips:
    post:
      tags: [片段管理]
      summary: 上传音频文件并创建片段
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [file, consentId, format]
              properties:
                file: { type: string, format: binary }
                consentId: { type: string, format: uuid }
                format: { type: string, enum: [MP3, M4A, WAV, FLAC, OGG] }
                durationMs: { type: integer, minimum: 500, maximum: 300000 }
                fileName: { type: string, maxLength: 120 }
      responses:
        "201": { description: 创建成功, content: { application/json: { schema: { $ref: '#/components/schemas/ClipResponse' } } } }
        "400": { description: 输入校验失败 }
        "401": { description: 未认证 }
        "403": { description: 授权不可用 }
        "404": { description: 授权不存在 }
        "413": { description: 文件超过 25 MB }
        "415": { description: 格式不受支持 }
  /api/v1/clips/{clipId}:
    get:
      tags: [片段管理]
      summary: 查询片段
      security: [{ bearerAuth: [] }]
      parameters:
        - { name: clipId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        "200": { description: 查询成功, content: { application/json: { schema: { $ref: '#/components/schemas/ClipResponse' } } } }
        "400": { description: clipId 非 UUID }
        "401": { description: 未认证 }
        "404": { description: 片段不存在/不属于当前用户/已过期 }
```

## 十一、实现批次配置项（约定，不在本轮落地）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `musicbridge.clips.max-size-bytes` | `26214400` | 25 MB，按文件实际字节 |
| `musicbridge.clips.max-duration-ms` | `300000` | 5 分钟，按实测时长 |
| `musicbridge.clips.min-duration-ms` | `500` | 过短拒收 |
| `musicbridge.clips.duration-tolerance-ms` | `2000` | 客户端提示值与实测值的最大偏差 |
| `musicbridge.clips.cleanup-cron` | `0 0 3 * * *` | 每日清理 |
| `spring.servlet.multipart.max-file-size` | `25MB` | 容器层粗筛，业务层仍需按实际字节复核 |
| `spring.servlet.multipart.max-request-size` | `26MB` | 允许少量表单开销 |

## 十二、契约检查清单（供确认后进入实现）

- [ ] 25 MB 按文件实际字节数校验，multipart 开销不计入。
- [ ] 服务端校验魔数/容器特征，不只信扩展名与 MIME。
- [ ] 服务端探测实际时长并以其为准；客户端 `durationMs` 仅作提示，偏差 > 2s 拒绝。
- [ ] 上传前校验授权与 `retentionDays ≥ 1`；写入完成前再次校验，撤销即删文件并 403。
- [ ] `userId` 仅来自认证上下文；跨用户一律 404。
- [ ] 存储路径固定为 `sha256(userId)/consentId/clipId.ext`。
- [ ] 过期片段即使未物理清理也不可读取、不可用于识曲（404）。
- [ ] 清理每日定时 + 启动补扫；幂等、可重试、不递归清空用户目录。
- [ ] 不提供 `DELETE /api/v1/clips/{clipId}`，服务层保留 `ClipService.delete`。
- [ ] 音频探测方案（A 手写 / B jaudiotagger）确认，若新增依赖需报备体积与传递依赖。
- [ ] OpenAPI 实现批次补齐 413/415 映射与 schema。
