# MusicBridge Phase 0 验收清单

## 基线

| 项 | 值 |
| --- | --- |
| 报告文件 | `research/musicbridge-phase1/MusicBridge-第一阶段调研报告.md` |
| 报告 SHA256（第二版基线） | `F4C406116C630D367028AC68D767AD9FF774C86E66C6699486DD46B273E406DB` |
| 第一版报告 SHA256 | `71AA764A7C6B463649A0EC0B4E882AC613E68C9C30921825EEAEF41A8DFAE8CE` |
| 核查日期 | 2026-11-06 |
| 版本说明 | 第二版基线：对应报告第六轮（数据库 MySQL 8.x）；报告任何改动须重新核对并更新哈希 |
| 基线变更原因 | 数据库由 PostgreSQL 改为 MySQL 8.x（第六轮），同步技术栈、DDL、迁移注意点与验收口径，哈希随之更新 |

> 判定规则：每项结果只填“通过 / 不通过 / 不适用”；必须附可复现证据（命令输出、测试报告、接口响应、日志片段），仅有代码存在不得标记通过；每完成一批任务更新核对表。

## 目录与条目总数

共 61 条：A 工程 5；B Provider 层 12；C Mock 与契约测试 6；D 数据库 18；E 接口层 8；F 安全与成本 8；G 范围排除 4。

- [A 工程](#a-工程)（5）
- [B Provider 层](#b-provider-层)（12）
- [C Mock 与契约测试](#c-mock-与契约测试)（6）
- [D 数据库](#d-数据库)（18）
- [E 接口层](#e-接口层)（8）
- [F 安全与成本](#f-安全与成本)（8）
- [G 明确不在 Phase 0 范围](#g-明确不在-phase-0-范围确认未包含)（4）
- [核对表](#核对表)

## 最小实现边界

优先保证核心结构与契约（接口、DTO、表、配置）可编译、可迁移、可测试，再实现对应最小可测版本；不得为凑验收项提前实现 Phase 1 及之后的业务功能（歌词业务、翻译/音译、桌面端、音频采集、真实 Provider 接入）。占位接口/表必须可编译或可迁移，不能用空壳凑数。

## A 工程

- [ ] **E-01** 可编译：`mvn -q -DskipTests package` 或 `./gradlew build -x test` 退出码为 0；证据：构建日志。
- [ ] **E-02** 可启动：连接本机 MySQL 8.x（≥8.0.16）后应用启动成功，启动日志无 `ERROR`；证据：启动日志。
- [ ] **E-03** 仅本机监听：启动后端口绑定 `127.0.0.1`，不是 `0.0.0.0`；证据：`netstat -ano | findstr :<端口>`。
- [ ] **E-04** 配置齐全：数据源、Flyway、Provider 端点/密钥占位、超时、限流、缓存 TTL 均在配置文件中；证据：`application*.yml`。
- [ ] **E-05** 版本已实测记录：提交包含 `java -version`、`mvn -v` 或 `gradle -v`、Spring Boot 版本；证据：版本记录/构建日志。

## B Provider 层

- [ ] **P-01** 五类接口存在：Recognition / Metadata / Lyrics / Translation / Transliteration；`LyricsProvider` 仅接口占位，不含歌词业务；证据：`src/main/java/**/provider/`。
- [ ] **P-02** `RecognitionRequest` 字段与报告 8.2 一致：`inputType`、`clipId`、`fingerprint`、`audioDuration`、`consentId`、`languageHint`；证据：DTO 源码。
- [ ] **P-03** `RecognitionCandidate` 字段与报告 8.2 一致：`title`、`artists`、`album`、`duration`、`candidateReleaseHint`、`confidence`、`externalIds`、`debugReference`；不存在 `raw` 或原始响应字段；证据：DTO 源码。
- [ ] **P-04** `ProviderRegistry` 支持注册、启用/停用、优先级设置；证据：Registry 源码 + 单测。
- [ ] **P-05** `ProviderManager` 调用前执行 `supports(inputType)` 过滤，不支持时不发起调用；证据：Manager 源码 + 路由单测。
- [ ] **P-06** 每个 Provider 有显式连接/读取超时，默认值来自配置，不存在无限等待；证据：配置 + 延迟 Mock 测试。
- [ ] **P-07** 8 类错误码齐全：`CONFIG_ERROR`、`AUTH_INVALID`、`PERMISSION_DENIED`、`QUOTA_EXCEEDED`、`RATE_LIMITED`、`TEMPORARY_UNAVAILABLE`、`UNSUPPORTED_INPUT`、`NO_MATCH`；403 区分功能级/账号级；证据：错误码枚举 + 映射单测。
- [ ] **P-08** 重试上限：`TEMPORARY_UNAVAILABLE` 首次请求 1 次 + 额外最多 3 次重试（指数退避）；`RATE_LIMITED` 按 `Retry-After` 额外最多 1 次；均受 P-09 单任务总调用预算约束；证据：重试单测。
- [ ] **P-09** 切换上限与总预算：单次调用最多切换 2 次；单次任务外部调用（首次请求+重试+切换+429）合计 ≤5 次；证据：预算计数单测。
- [ ] **P-10** 熔断：连续 429 达 3 次进入 `OPEN`，冷却 ≥60s，半开只放行 1 次；证据：熔断单测。
- [ ] **P-11** 调用日志含报告 8.9 字段：`trace_id`、`provider`、`capability`、`input_type`、`request_units`、`http_status`、`provider_error_code`、`normalized_error_code`、`retry_count`、`retry_after`、`latency_ms`、`from_provider`/`to_provider`、`switch_reason`、`estimated_cost`、`cache_hit`、`occurred_at`；证据：表结构 + 日志样例。
- [ ] **P-12** 原始 Provider 响应不进入业务 DTO、数据库和普通日志；证据：DTO/实体/日志代码检查。

## C Mock 与契约测试

- [ ] **M-01** 五类能力各至少一个 Mock Provider；证据：`src/test/**/provider/mock/`。
- [ ] **M-02** 测试不依赖外网与真实密钥：断网或禁用外网时测试仍通过，测试配置无真实 Key；证据：测试日志 + 配置扫描。
- [ ] **M-03** 路由覆盖：`RAW_AUDIO` 只路由到支持原始音频的 Provider，`AUDIO_FINGERPRINT` 只路由到支持指纹的 Provider；证据：路由单测。
- [ ] **M-04** 同 `inputType` 才可切换：跨输入类型修改 `enabled/priority` 不会触发调用，返回明确错误；证据：切换单测。
- [ ] **M-05** `RAW_AUDIO` 失败后不会把原始音频交给仅指纹 Provider；证据：失败降级单测。
- [ ] **M-06** 契约测试离线执行通过：`mvn test` 或 `./gradlew test` 退出码为 0；证据：测试报告。

## D 数据库

- [ ] **D-01** Flyway 可在空 MySQL 库执行成功，重复执行无破坏；证据：`flyway migrate` 日志。
- [ ] **D-02** 12 张核心表存在：`work`、`recording`、`release`、`release_track`、`recording_relation`、`lyrics`、`lyric_line`、`local_media`、`external_ref`、`upload_consent`、`music_event`、`provider_call_log`（`release` 表在 SQL 中使用反引号）；歌词表仅建结构与约束，不含歌词业务；证据：`mysql -e 'SHOW TABLES'`。
- [ ] **D-03** `work`：`mb_work_id` 可空且 UNIQUE；`is_local_placeholder` NOT NULL；`source_provider` NOT NULL；`source_record_id` 仅 `source_provider=LOCAL` 时可为空（CHECK）；证据：迁移 SQL + `SHOW CREATE TABLE work`。
- [ ] **D-04** `recording`：`work_id` NOT NULL 外键；`source_record_id` NOT NULL；`version_type` NOT NULL 默认 `ORIGINAL`；`mb_recording_id` UNIQUE；证据：迁移 SQL + `SHOW CREATE TABLE recording`。
- [ ] **D-05** `release_track`：`release_id`、`recording_id` NOT NULL；`UNIQUE(release_id, disc_number, track_number)`；证据：迁移 SQL。
- [ ] **D-06** `lyrics`：`language` NOT NULL；`UNIQUE(recording_id, kind, language, provider_id)`；`plain_text` 为空时必须有 `lyric_line`（应用层/触发器校验）；本阶段仅建表与约束，不接入歌词数据；证据：迁移 SQL + 约束测试。
- [ ] **D-07** `lyric_line`：`UNIQUE(lyrics_id, line_no)`；`start_ms`/`end_ms` 非负且 `end_ms >= start_ms`；证据：迁移 SQL + 约束测试。
- [ ] **D-08** `recording_relation`：禁止自指；`relation_scope` 仅 `SAME_WORK_DIFFERENT_RECORDING`；`relation_subtype` 仅 COVER/LIVE/REMIX/INSTRUMENTAL；生成列 `pair_low`/`pair_high` 与 `relation_scope` 组成唯一键（替代 PostgreSQL 表达式索引）；证据：迁移 SQL + `SHOW CREATE TABLE recording_relation` + 约束测试。
- [ ] **D-09** `local_media`：`recording_id` 可空；`UNIQUE(user_id, file_path)`；`file_hash` 为普通索引；`file_path` 注释标明桌面端索引、远程库不存绝对路径；证据：迁移 SQL。
- [ ] **D-10** `external_ref`：`UNIQUE(id_type, id_value)`；`entity_type`/`entity_id` NOT NULL；应用层校验实体存在并清理 orphan；证据：迁移 SQL + 校验代码。
- [ ] **D-11** `upload_consent`：`user_id`、`scope`、`purpose`、`granted_at`、`retention_days` NOT NULL；`revoked_at`、`expires_at` 可空；证据：迁移 SQL。
- [ ] **D-12** `music_event`：`event_type` 仅 `RECOGNIZED`/`CONFIRMED`/`ADDED_TO_LIBRARY`；`recording_id` 可空；`dedupe_key` UNIQUE；证据：迁移 SQL + 约束测试。
- [ ] **D-13** 不存在 `track` 表；旧命名仅作为 deprecated 别名；证据：`SHOW TABLES` + 代码搜索。
- [ ] **D-14** 报告 9.4 的关键索引已创建：`idx_recording_work`、`idx_local_hash`、`idx_local_fingerprint`、`idx_external_ref_value`、`idx_consent_user`、`idx_lyrics_recording_kind`、`idx_relation_target`、`idx_event_user_time` 等；证据：`SHOW INDEX FROM <表>` 或 `information_schema.statistics`。
- [ ] **D-15** 数据库升级：已有数据的库执行后续 Flyway 迁移后原数据完整（行数、关键字段、外键与唯一约束无丢失）；证据：迁移前后数据对比脚本输出。
- [ ] **D-16** MySQL 版本 ≥8.0.16，CHECK 实际生效：`SELECT VERSION()` 满足要求；向 `work_source_chk`、`line_time_chk`、`relation_no_self`、`event_type_chk` 等插入非法值被拒绝；证据：版本查询 + 约束测试。
- [ ] **D-17** 唯一约束与 NULL：`mb_work_id`、`mb_recording_id`、`mb_release_id`、`dedupe_key` 允许多行 NULL，非空值重复被拒绝；“仅非空唯一”的列已显式 NOT NULL 或用生成列归一化；证据：插入测试。
- [ ] **D-18** 字符集、引擎与排序规则：12 张表均为 `ENGINE=InnoDB`、`DEFAULT CHARSET=utf8mb4`；`external_ref.id_value`、`local_media.file_path` 使用区分大小写排序规则（`utf8mb4_0900_as_cs` 或 `utf8mb4_bin`）；UUID 列为 `CHAR(36)` + `ascii_bin`；证据：`SHOW CREATE TABLE` 抽样。

## E 接口层

- [ ] **A-01** OpenAPI 可访问：`GET /v3/api-docs` 返回 200 且 JSON 可解析；证据：curl 输出/截图。
- [ ] **A-02** DTO 与实现一致：Work/Recording/Release/ReleaseTrack 字段名与报告 8.4 一致；证据：OpenAPI schema + DTO 源码。
- [ ] **A-03** `/clips` 仅接受 `multipart`，拒绝客户端本地路径；≤12 秒、≤5 MB、16–48 kHz、WAV/MP3/M4A/FLAC；证据：接口测试。
- [ ] **A-04** `/recognitions` 只引用 `clipId` 或指纹，不接收音频二进制，不承担片段生命周期；证据：接口测试。
- [ ] **A-05** 归属校验：跨用户 `clipId`/`consentId` 返回 404，scope 不匹配返回 403；证据：双用户测试。
- [ ] **A-06** 输入校验：`RAW_AUDIO` 必填 `clipId`+有效 `consentId`；`AUDIO_FINGERPRINT` 必填 `fingerprint`+`audioDuration`，不要求 `consentId`；不匹配返回 400 `INVALID_INPUT`；证据：接口测试。
- [ ] **A-07** 授权撤销后：停止新上传、取消未开始任务、清理本地临时片段；证据：撤销流程测试。
- [ ] **A-08** 身份认证：明确用户身份的建立、认证与失效机制；客户端不得任意传入 `userId` 冒充他人；未认证请求访问受保护资源返回 401；本机部署采用最小方案（本地会话令牌即可），不要求复杂认证系统；证据：认证接口测试 + 配置。

## F 安全与成本

- [ ] **S-01** 仓库无真实密钥：AudD/ACRCloud/AcoustID 等 Key 仅为环境变量或占位；证据：全仓密钥扫描。
- [ ] **S-02** 无真实计费调用：测试与启动过程只调用 Mock，`provider_call_log` 无真实 Provider 记录；证据：日志/表查询。
- [ ] **S-03** 限流有最小实现：Provider 统一限流模块（进程内令牌桶/信号量 + Caffeine），多实例共享存储接口已预留；证据：限流代码 + 并发测试。
- [ ] **S-04** 缓存有最小实现：查询缓存、single-flight、TTL 配置、负缓存可用；证据：缓存代码 + 单测。
- [ ] **S-05** 调用可追溯：按 `trace_id` 可查完整调用链，日志不含密钥/音频/指纹原文/绝对路径；证据：日志查询。
- [ ] **S-06** 生产配置无 Mock，Mock 仅在 dev/test 配置启用；证据：配置检查。
- [ ] **S-07** 本机 API 访问保护：明确允许的客户端来源（如 Electron 固定 Origin/本机回环），拒绝未授权跨源请求；敏感操作校验会话身份；采用适合 `127.0.0.1` 单机的最小方案；证据：跨源请求测试 + CORS/Origin 配置。
- [ ] **S-08** 敏感信息保护：密钥、数据库密码、音频内容、指纹原文不出现在普通日志与异常响应；检查 Git 历史无泄漏；证据：日志/异常响应抽样 + 密钥扫描报告（含 `git log -S`）。

## G 明确不在 Phase 0 范围（确认未包含）

- [ ] **N-01** 不含桌面客户端（Electron/前端工程、文件选择、片段截取 UI）。
- [ ] **N-02** 不含歌词业务实现（第三方歌词接入、LRC 解析、翻译、音译）；允许 `lyrics`/`lyric_line` 表与 `LyricsProvider` 接口占位。
- [ ] **N-03** 不含音频采集（系统回环、麦克风、静音检测）。
- [ ] **N-04** 不含真实 Provider 接入与实际第三方调用；允许接口、Registry、Manager、Mock、契约测试及报告 9 规定的表结构与配置。

## 更新记录

- 2026-11-06：新增 A-08、D-15、S-07、S-08；明确歌词边界、重试计数与最小实现边界；条目总数 58。
- 2026-11-06（第二版）：数据库改为 MySQL 8.x；E-02、D-01/D-02/D-03/D-04/D-08/D-13/D-14 改 MySQL 口径；新增 D-16 CHECK 8.0.16+ 生效、D-17 唯一约束 NULL、D-18 utf8mb4/InnoDB/排序规则；条目总数 61；报告基线更新为第二版。
- 2026-11-06（第三批补齐）：完成身份认证与 CORS（A-08、S-07）、授权撤销与跨用户隔离（A-05/A-07 部分通过）、trace/single-flight/并发限流（S-03/S-04/S-05）、D-15 升级对比、Maven Wrapper、git 仓库与密钥历史扫描（S-01/S-08）。

## 核对表

| 编号 | 验收项 | 结果 | 证据 | 备注 |
| --- | --- | --- | --- | --- |
| E-01 | 可编译 | 通过 | `mvn -B -DskipTests package` → `BUILD SUCCESS`，生成 `target/musicbridge-backend-0.1.0-SNAPSHOT.jar`（09:18）；`.\mvnw.cmd -B clean test` 从零构建 `BUILD SUCCESS`（10:15） | — |
| E-02 | 可启动 | 通过 | `.\mvnw.cmd -DskipTests package` 后 `java -jar ... --spring.profiles.active=local`（10:18）：`HikariPool-1 - Start completed`；Flyway `Successfully validated 3 migrations`、`Current version of schema \`musicbridge_test\`: 3`；`Tomcat started on port 8080`；`Started MusicBridgeApplication in 5.0 seconds`；本次运行 WARN/ERROR 除 Flyway 版本提示外为 0（两条 JSON 解析 WARN 由测试命令引号导致，非应用缺陷） | 连接本机 MySQL 8.4.7 |
| E-03 | 仅本机监听 | 通过 | `netstat -ano \| findstr :18080` → `TCP 127.0.0.1:18080 0.0.0.0:0 LISTENING`（PID 20916） | 使用 `application-local.yml` 的 `server.address=127.0.0.1` |
| E-04 | 配置齐全 | 不通过 | `application.yml` 含 datasource、Flyway、Provider 端点/密钥占位、timeout、限流参数；缓存 TTL 在 `CacheCategory` 枚举中，未外置到配置文件 | 缓存 TTL 未配置化 |
| E-05 | 版本已实测记录 | 通过 | `java version "21.0.5"`；`Apache Maven 3.9.16`；`.\mvnw.cmd -v` → `Apache Maven 3.9.16`（Wrapper 固定版本，`distributionUrl` 指向 3.9.16）；Spring Boot 3.5.6（`pom.xml` parent） | — |
| P-01 | 五类接口存在 | 通过 | `provider/recognition`、`metadata`、`lyrics`、`translation`、`transliteration` 各含接口；`LyricsProvider` 仅 `fetch(LyricsQuery, RequestContext)` 契约 | 歌词无业务实现 |
| P-02 | `RecognitionRequest` 字段 | 通过 | `RecognitionRequest` record：`inputType`、`clipId`、`fingerprint`、`audioDuration`、`consentId`、`languageHint` | — |
| P-03 | `RecognitionCandidate` 字段 | 通过 | record 含 `title`、`artists`、`album`、`duration`、`candidateReleaseHint`、`confidence`、`externalIds`、`debugReference`；无 `raw` 字段 | — |
| P-04 | Registry 注册/启停/优先级 | 通过 | `ProviderRegistry.register/find/candidates/enabledCandidates`；启用与优先级来自 `ProviderProperties.ProviderSetting`；`ProviderManagerRoutingTest` 通过 | 运行期停用由 `ProviderRuntimeState` 承担 |
| P-05 | 输入类型过滤 | 通过 | `ProviderManager.selectCandidates` 先执行 `task.providerFilter()`；`skipsProviderThatDoesNotSupportInputType` 通过 | — |
| P-06 | 显式超时 | 通过 | `ProviderSetting.timeoutMs` 默认 15000；`ProviderManager` 调用 `callExecutor.call(Duration.ofMillis(setting.getTimeoutMs()), ...)`；`timesOutSlowProviderAndFallsBack` 通过（1 次超时后切换） | 延迟 Provider 测试 |
| P-07 | 8 类错误码与 403 区分 | 通过 | `ProviderErrorCode` 8 个枚举值；`PermissionScope`=CAPABILITY/ACCOUNT/UNKNOWN；`doesNotSwitchOnAuthInvalidAndDisablesProvider`、`switchesOnCapabilityLevelPermissionDenied` 通过 | — |
| P-08 | 重试上限 | 通过 | `retriesTemporaryFailureOnePlusThreeTimesAtMost` 断言临时故障共 4 次调用（首次 1 + 额外 3）；429 测试断言额外 1 次（共 2 次） | 受 P-09 预算约束 |
| P-09 | 切换与总预算 | 通过 | `stopsSwitchingWhenBudgetIsExhausted`、`capsTotalCallsByBudget` 通过；`policy` 为 5 次调用、2 次切换 | — |
| P-10 | 熔断 | 通过 | `opensCircuitAfterConsecutiveFailuresAndSkipsProvider`：连续 3 次失败后 `CircuitState.OPEN`，后续请求跳过；`policy` 为 3/60000ms/半开 1 | — |
| P-11 | 调用日志字段 | 通过 | `ProviderCallLog` 17 个字段；`V2__provider_call_log.sql` 对应列；路由测试断言 `fromProvider`/`toProvider`/`switchReason`/`cacheHit` | — |
| P-12 | 原始响应不入库/日志 | 通过 | `RecognitionCandidate` 仅规范字段 + `debugReference`；无原始响应字段；无真实 Provider 实现 | — |
| M-01 | 五类能力 Mock | 通过 | `provider/mock` 下 5 个实现；`MockProviderContractTest` 6 项通过 | — |
| M-02 | 不依赖外网与真实密钥 | 通过 | 测试全部使用进程内 Provider；配置密钥均为 `${...}` 占位；`mvn -B test` 通过 | — |
| M-03 | 按输入类型路由 | 通过 | `skipsProviderThatDoesNotSupportInputType`、`rawAudioFailureDoesNotFallBackToFingerprintOnlyProvider` 通过 | — |
| M-04 | 同输入类型才可切换 | 通过 | `selectCandidates` 在启用/优先级之前按能力与输入类型过滤；跨类型 Provider 不进入候选（`skipsProviderThatDoesNotSupportInputType`） | — |
| M-05 | RAW_AUDIO 不降级到指纹 Provider | 通过 | `rawAudioFailureDoesNotFallBackToFingerprintOnlyProvider`：raw-primary 失败后 fingerprint-only 调用数为 0 | — |
| M-06 | 契约测试离线通过 | 通过 | `.\mvnw.cmd -B clean test` → `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（2026-11-06 10:15）；报告在 `target/surefire-reports` | — |
| D-01 | Flyway 空库与重复执行 | 通过 | `MySqlMigrationTest`：`clean` 后 `Successfully applied 3 migrations ... now at version v3`；再次 migrate `migrationsExecuted=0`，`work`/`recording` 数据保留 | Flyway 11.7.2 对 MySQL 8.4 提示“支持未测试”（官方最高 8.1）；实测迁移、约束、升级均成功（见 D-15/D-16） |
| D-02 | 12 张核心表存在 | 通过 | `SHOW TABLES` 输出 18 张表，含 12 张核心表与 `user_session`、`field_provenance`、`playlist`、`playlist_item`、`release_track` 等；`release` 在 SQL 中使用反引号 | 新增 V3 `user_session` |
| D-03 | `work` 约束 | 通过 | `work_source_chk` 拒绝非 LOCAL 且无 `source_record_id` 的插入（`enforcesRemainingCoreConstraints`）；`mb_work_id` UNIQUE 且可空 | — |
| D-04 | `recording` 约束 | 通过 | `work_id`/`source_record_id` NOT NULL、`version_type` 默认 ORIGINAL、`mb_recording_id` UNIQUE；`recording_version_chk` 拒绝 `INVALID`（`enforcesCoreConstraints`） | — |
| D-05 | `release_track` 约束 | 通过 | `release_id`/`recording_id` NOT NULL；`uq_release_track_position (release_id, disc_number, track_number)` | — |
| D-06 | `lyrics` 约束 | 不通过 | `language` NOT NULL、`uq_lyrics_recording_kind_lang_provider` 已建；`plain_text` 为空时必须有 `lyric_line` 的跨表校验未实现 | Phase 0 不做歌词业务 |
| D-07 | `lyric_line` 约束 | 通过 | `uq_lyric_line_no`；`lyric_line_time_chk` 拒绝 `start_ms=500, end_ms=100`（`enforcesRemainingCoreConstraints`） | — |
| D-08 | `recording_relation` 约束 | 通过 | 生成列 `least_recording_id`/`greatest_recording_id`（等价 pair_low/pair_high）+ `uq_relation_canonical`；A→B 与 B→A 重复被拒；自指被拒；scope/subtype CHECK 存在 | 列名与清单示意名不同，语义一致 |
| D-09 | `local_media` 约束 | 通过 | `recording_id` 可空；`uq_local_media_user_path`；`idx_local_hash`；SQL 注释标明桌面端索引、远程不存绝对路径 | — |
| D-10 | `external_ref` 约束 | 不通过 | `uq_external_ref_type_value` 已建；应用层实体存在校验与 orphan 清理未实现 | — |
| D-11 | `upload_consent` 约束 | 通过 | `user_id`/`scope`/`purpose`/`granted_at`/`retention_days` NOT NULL；`revoked_at`/`expires_at` 可空 | — |
| D-12 | `music_event` 约束 | 通过 | `music_event_type_chk` 拒绝 `BAD`；`recording_id` 可空；`uq_music_event_dedupe` | — |
| D-13 | 不存在 `track` 表 | 通过 | 测试断言 `tableExists("track")=false`；`SHOW TABLES` 无 track | — |
| D-14 | 关键索引 | 通过 | `schemaUsesMysqlConventionsAndKeyIndexes` 校验 idx_recording_work、idx_local_hash、idx_local_fingerprint、idx_external_ref_value、idx_consent_user、idx_lyrics_recording_kind、idx_relation_target、idx_event_user_time 均存在 | — |
| D-15 | 数据库升级保留数据 | 通过 | 新增 `upgradeFromV2PreservesExistingData`：Flyway `target("2")` 建基线并写入 work/recording/external_ref/upload_consent/user_library/music_event/provider_call_log 数据 → 升级到 v3 → 7 张表行数逐表相等、`work.title` 仍为“晴天”、`external_ref` 值未变、`user_session` 新增且为 0 行；`MySqlMigrationTest` 5 项通过 | — |
| D-16 | MySQL ≥8.0.16 且 CHECK 生效 | 通过 | `SELECT VERSION()`=8.4.7；work_source_chk、lyric_line_time_chk、recording_relation_no_self、music_event_type_chk 均拒绝非法值 | — |
| D-17 | 唯一约束与 NULL | 通过 | `mb_work_id` 多行 NULL 允许、重复非空被拒；`dedupe_key` 多行 NULL 允许 | 其余 UNIQUE 列按 MySQL NULL 语义 |
| D-18 | 字符集/引擎/排序规则 | 通过 | information_schema：18 表 `InnoDB` + `utf8mb4_0900_ai_ci`；`work.id`/`provider_call_log.trace_id`/`user_session.token_hash`=`ascii_bin`；`external_ref.id_value`/`local_media.file_path`=`utf8mb4_0900_as_cs` | — |
| A-01 | OpenAPI 可访问 | 通过 | `GET /v3/api-docs` → HTTP 200（09:24 于 18080；10:18 于 8080 再次验证）；`openapi=3.1.0`，`title=MusicBridge Backend API` | — |
| A-02 | DTO 与 OpenAPI 一致 | 不通过 | Work/Recording/Release/ReleaseTrack DTO 源码存在；当前 OpenAPI 只暴露 3 个 admin 端点，四层 DTO 未进入 schema | 缺业务 REST 端点 |
| A-03 | `/clips` 接口 | 不通过 | 未实现 `/clips` multipart 接口与媒体校验 | 不在本批范围 |
| A-04 | `/recognitions` 接口 | 不通过 | 未实现 `/recognitions` 接口 | 不在本批范围 |
| A-05 | 归属校验 | 不通过 | `crossUserConsentAccessIsIsolated`：跨用户读取/撤销 consent 返回 404、列表为空 —— consentId 部分通过；`clipId` 与 scope 不匹配返回 403 未实现（无 `/clips`） | 部分通过 |
| A-06 | 输入校验 | 不通过 | 无 REST 输入校验实现；字段规则仅写在 DTO 注释 | — |
| A-07 | 授权撤销流程 | 不通过 | `revokedConsentCannotBeUsedForUpload`：撤销后 `verify` 返回 403、`active=false`、`revokedAt` 有值 —— 撤销后禁止上传已通过；本地临时片段清理未实现 | 部分通过 |
| A-08 | 身份认证 | 通过 | `SecurityApiTest` 9 项通过：错误密码 401；登录后 `GET /api/v1/auth/me` 返回服务端 `local-user`（伪造 `?userId=attacker` 不生效）；缺失/无效令牌 401；`logout` 后原令牌 401；令牌仅存 SHA-256 哈希（`V3__security_session.sql`）；`localPassword` 只读环境变量 | — |
| S-01 | 仓库无真实密钥 | 通过 | git 历史全量扫描：`git rev-list --all` + `git grep -E 'BEGIN ... PRIVATE KEY\|AKIA...'` 无命中；凭据赋值模式仅命中 `System.getenv("MYSQL_TEST_PASSWORD")` 等环境变量读取；密钥均为 `${AUDD_API_KEY:}` 占位 | — |
| S-02 | 无真实计费调用 | 通过 | `provider_call_log` 行数 0；`provider` 下仅 `mock` 实现 | — |
| S-03 | 限流最小实现 | 通过 | `ConcurrencyReliabilityTest.rateLimiterBlocksConcurrentCallsBeyondBurstCapacity`：8 线程并发、`burst=1`，实际 Provider 调用 1 次、1 个成功、7 个失败（首个为 `RATE_LIMITED` 并写入调用日志） | 冷却后的请求表现为 `NO_MATCH` |
| S-04 | 缓存最小实现 | 通过 | Caffeine 缓存、TTL 矩阵、负缓存已实现；新增 `SingleFlight.run(key, supplier)` 包裹 `ProviderManager.execute`；`singleFlightCollapsesConcurrentIdenticalRequests`：8 个同键并发请求只触发 1 次 Provider 调用，结束后在途标记清零 | — |
| S-05 | 调用可追溯 | 通过 | `TraceFilter` 接受/生成 `X-Trace-Id` 并写入 MDC 与响应头；`RequestContext` 复用该 traceId；`GET /api/v1/admin/traces/{traceId}` 返回该链路的 Provider 调用日志；`TraceObservabilityTest` 5 项通过；运行日志实测 `INFO [trace=aa450de9-bc1c-4a32-96ff-faa31f5f5893]`、`WARN [trace=23ab8751-...]`，响应头回显 `X-Trace-Id: trace-live-001` | 日志目前为内存环形记录，未持久化到 `provider_call_log` 表 |
| S-06 | 生产配置无 Mock | 通过 | `application-local.yml` 启用 5 个 mock；`application-remote.yml` 全部 `enabled: false`；`ProviderRegistry` 对 `prod` 环境校验 Mock 开关 | — |
| S-07 | 本机 API 访问保护 | 通过 | `CorsConfig` 读 `musicbridge.security.cors.allowed-origins`：local 固定 `http://localhost:5173`、`http://127.0.0.1:5173`，remote 走 `MUSICBRIDGE_CORS_ALLOWED_ORIGINS` 且默认空；`SecurityApiTest`：允许来源返回 `Access-Control-Allow-Origin`，`http://evil.example` 被 403 拒绝，预检 200；运行实例实测允许来源 `ACAO=http://localhost:5173`、`Access-Control-Expose-Headers: X-Trace-Id`，`evil.example` → 403 | — |
| S-08 | 敏感信息保护 | 通过 | git 仓库已建立（`c:\Users\联想电脑\Desktop\SOA\项目`，提交 `9d04bd6`、`2f23a5c`）；`git rev-list --all` + `git grep` 扫描私钥/AKIA 无命中；`git log -S` 全历史中 123456 仅命中验收文档自述文字，无任何配置文件或脚本；`.gitignore` 排除 `.env*`、`application-local.yml`（保留无密钥的开发 profile）、`*.pem/*.key/*.p12/*.jks`、`secrets/` | 本机口令仅经运行时环境变量传入 |
| N-01 | 不含桌面客户端 | 通过 | 工程仅后端；无 `package.json`、Electron 或前端目录 | — |
| N-02 | 不含歌词业务 | 通过 | 仅 `lyrics`/`lyric_line` 表与 `LyricsProvider` 接口；无 LRC 解析、翻译、音译实现 | — |
| N-03 | 不含音频采集 | 通过 | 代码中无系统回环、麦克风、静音检测实现 | — |
| N-04 | 不含真实 Provider 接入 | 通过 | 仅 Mock Provider 与配置占位；无真实第三方调用 | — |

> 本表证据采集时间：2026-11-06 10:20（Asia/Shanghai）。通过 52 项，不通过 9 项；不通过项均标注缺口，未以“代码存在”替代可复现证据。
