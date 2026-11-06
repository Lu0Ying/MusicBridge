# MusicBridge 项目进度记录

> 更新日期：2026-11-06
> 当前阶段：Phase 0 已冻结，进入 Phase 1

## 一、Phase 0 冻结状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 验收结果 | **57 / 61** | 逐项证据见 [`research/musicbridge-phase1/PHASE0_ACCEPTANCE.md`](research/musicbridge-phase1/PHASE0_ACCEPTANCE.md) |
| 自动化测试 | **76 项全过** | `.\mvnw.cmd -B clean test`（`MB_RUN_MYSQL_IT=true`）：Tests run: 76, Failures: 0, Errors: 0, Skipped: 0 |
| Git 基线 | **`109c3b2`** | 提交说明：补齐 Phase 0 剩余 5 项非端点缺口 |
| 分支策略 | **`master`** | 保留 `master`，不改名为 `main`；upstream 为 `origin/master` |
| 远端 | `git@github.com:Lu0Ying/MusicBridge.git` | 已推送，`origin/master` 与本地 `master` 一致（0 领先 / 0 落后） |
| 数据库 | **MySQL 8.4.7** | 测试库 `musicbridge_test`；迁移脚本 `musicbridge-backend/src/main/resources/db/migration` |
| 迁移工具 | **Flyway 11.7.2** | 保留兼容性告警，后续回归（见下） |

### 冻结口径

- Phase 0 范围内不再新增业务端点与业务逻辑；如需变更，先重新打开验收清单并说明理由。
- 本阶段交付的是工程骨架与质量基线，不包含 `/clips`、`/recognitions` 等 Phase 1 业务端点。
- 密码与密钥只走环境变量或未跟踪的本地配置，仓库内不保存真实凭据。

### 已知遗留与非缺陷说明

1. **未完成验收项（4 项）**：A-02、A-03、A-04、A-05，全部依赖 Phase 1 业务端点，已在验收清单中标注归属，不计入 Phase 0 缺陷。
2. **Flyway 兼容性告警**：Flyway 11.7.2 对 MySQL 8.4.7 提示
   `Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested.`
   决策为保持 11.7.2、不升级、不通过降低日志级别掩盖告警；迁移、约束与升级前后数据对比均已实测通过，保留后续回归事项（升级 Flyway 时复跑完整迁移与集成测试）。
3. **`TempFragmentService` 未接入上传端点**：属于 Phase 0 的预留能力（用户隔离目录、在途上传跳过、撤销后清理），当前没有上传端点可接，**不是 Phase 0 缺陷**；已明确排入 **Phase 1.2**。

## 二、Phase 1 推进顺序与边界

原则：先打通“本机桌面端 ↔ 本机后端”的最小闭环，再补文件导入与片段管理，最后完成识曲闭环；第一版坚持**文件导入优先**，**系统音频采集留到 Phase 4**。

### Phase 1.1 Electron 最小桌面端

目标：形成一个可启动、可通信、可观测的最小桌面壳。

- Electron 工程骨架与开发/打包脚本
- 主窗口与基础页面（导入入口、识曲结果区、状态区）
- 与本机 Spring Boot 的通信：API Base URL 可配置（本地默认值 + 用户可改）
- 后端启停与健康检查（启动探测、失败提示、重试入口）
- 边界：不实现业务端点；不做音频采集；不做安装包签名与自动更新

### Phase 1.2 文件导入与片段管理

目标：完成“选文件 → 生成本地临时片段 → 授权校验 → 可上传/可清理”的链路。

- 文件选择与本地校验（类型、大小、可读性）
- 临时片段管理：创建、查询、过期与清理
- `/clips` 端点（新建片段、查询状态）
- 授权与临时文件生命周期衔接：上传前校验有效授权，撤销后停止新上传并清理片段
- **接入 `TempFragmentService`**：把已有清理能力挂到真实上传端点，覆盖在途上传与用户隔离
- 边界：不接真实识别 Provider；不做分片断点续传；不做云端存储

### Phase 1.3 识曲闭环

目标：完成“提交识别 → 结果展示 → 用户确认入库”的闭环。

- `/recognitions` 端点（提交识别、查询任务状态）
- Mock Provider 契约验证（与 Phase 0 的契约测试对齐）
- 结果展示（候选曲目、匹配信息、错误与重试提示）
- 用户确认入库（确认动作、幂等处理）
- 状态区分：`RECOGNIZED`（已识别）、`CONFIRMED`（用户已确认）、`ADDED_TO_LIBRARY`（已入库）
- 边界：不接真实 Provider；不做多 Provider 真实降级验证；不做系统音频采集

### 阶段外（Phase 4 及以后）

- 系统音频采集与实时识别
- 真实音乐识别 Provider 接入与配额/降级策略实测
- 歌词、翻译、外部标识等扩展能力的产品化

## 三、验证与提交约定

- 每次阶段交付前执行完整测试：`.\mvnw.cmd -B clean test`（需要数据库集成时设置 `MB_RUN_MYSQL_IT=true` 与 `MYSQL_TEST_*`）。
- 验收以实测证据为准，不以“代码存在”打勾。
- 本地提交后再按授权流程推送；禁止强制推送与跳过 hooks。

## 四、相关文档

- 验收清单：[`research/musicbridge-phase1/PHASE0_ACCEPTANCE.md`](research/musicbridge-phase1/PHASE0_ACCEPTANCE.md)
- 调研报告：[`research/musicbridge-phase1/MusicBridge-第一阶段调研报告.md`](research/musicbridge-phase1/MusicBridge-第一阶段调研报告.md)
- 后端工程：[`musicbridge-backend/`](musicbridge-backend/)
