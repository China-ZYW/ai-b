# 更新日志（Changelog）

本项目所有值得注意的变更都会记录在此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### Added
- **在线体验入口**：README 顶部与「🎯 在线体验」新章节挂载公网 Demo <https://iyun.ink/>，与仓库同版本；明确提醒 Demo 数据公共、会重置、勿输入真实凭据。
- **MCP 协议补齐**：`resources/templates/list` / `prompts/list` / `completion/complete` 分派与空实现；`initialize.capabilities` 声明扩展。
- **MCP `outputSchema`**：`list_datasources` / `list_metrics` 声明结构化返回值 JSON Schema，客户端可自动渲染。
- **MCP 传输协商**：`MCP-Protocol-Version` 请求头读取 + 响应头回显（不做拒绝，仅作可观测）。
- **SSE 心跳共享线程池**：消除每请求独立 executor；用 `ScheduledFuture.cancel` 精准收尾。
- **MCP 限流桶定时清理**：`McpRateLimiter.evictStale` 挂 `@Scheduled(fixedDelay=5min)`，与项目已有 `@EnableScheduling` 联动。
- **文档补齐**：新增 `docs/04-analysis/对外MCP能力方案.md`、`docs/05-operations/本地启动与联调.md`、`docs/05-operations/用户操作手册.md`、`SECURITY.md`、`CONTRIBUTING.md`、`CHANGELOG.md`、`CODE_OF_CONDUCT.md`、`.editorconfig`、`docker-compose.yml`、`frontend/Dockerfile`、`.github/` Issue/PR 模板与 CI workflow。
- **MCP 单测**：新增 `McpRateLimiterTest`（7 个）、`McpProtocolNegotiationTest`（3 个）。

### Fixed
- **文档文件名编码事故**：`docs/06-learning/AI练手项目指南.md` 磁盘名从 mojibake（GBK 误解 UTF-8）改回正确的中文；README 索引链接不再 404。

### Removed
- **本机路径泄露清理**：删除 `start-dev.bat`（含本机用户/Maven/Node/项目路径）；删除 `backend/run_tests.ps1`（同上）；`backend/run-serve.ps1` 改为按 `AIBI_JAVA_CMD` / `JAVA_HOME` / PATH 顺序解析 java 二进制。
- **落地页专有品牌**：`frontend/src/views/landing/landingContent.ts` 里 "WorkBuddy" 连接器改为通用的 "Claude Desktop 连接器"，客户端列表改为公开产品。

### Security
- 补齐 `SECURITY.md`：漏洞披露渠道、已内建五道安全红线说明、部署加固七条建议。
- `.gitignore` 全量补齐：凭据 / 构建产物 / 上传目录 / IDE / Python 缓存 / 回滚备份，防"未跟踪但未忽略"造成的意外入库。

---

## [0.1.0] - 初始开源发布

### Added
- **核心能力**：NL2SQL 对话式问数（多意图拆分、代价熔断、口径歧义反问、溯源回传、环比异常标记）
- **多租户 SaaS**：租户 / 用户 / 角色 / 数据范围 / 组织协同 / 邀请 / 席位与配额
- **多类型数据源**：MySQL / PostgreSQL / SQLite / SQL Server / Oracle / ClickHouse / Hive / Trino / Presto；CSV / Excel / Parquet 文件；S3 / OSS / MinIO
- **指标与可视化**：指标定义、去重、收藏；驾驶舱组件（柱/线/饼/雷达/漏斗/仪表盘/地图）；主题体系；公开分享
- **定时报表与消息**：动态相对时间变量；站内信 / 邮件 / 短信
- **可嵌入对话**：iframe 一行代码 + 独立令牌 + `allowedOrigins` 白名单
- **对外 MCP**：Streamable HTTP 单端点 `/mcp`；PAT 与 OAuth 2.1 资源服务器双鉴权；`tools/list` / `tools/call` / `resources/list` / `resources/read`；SSE 实时进度 + 心跳
- **安全治理**：只读 SQL 红线、字段脱敏、行级范围、并发护栏、双层限流、审计留痕、凭据 fail-fast 校验、AES-GCM 数据源凭据加密
- **文档**：产品可行性、SaaS 多租户、前后端架构、角色权限体系、AI 练手指南

[Unreleased]: https://github.com/your-org/aibi/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/your-org/aibi/releases/tag/v0.1.0
