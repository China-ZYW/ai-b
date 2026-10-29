# 对外 MCP 能力方案

> 目标：让 aibi 的"问数"能力能被外部 AI 客户端（Claude Desktop / Cursor / 豆包 / 自建 Agent / Dify 等）以标准协议直接调用，而不是只能在本系统的网页里用。
> 本文为方案文档：先讲清楚规范现状与能力映射，再给架构与安全边界，最后落地为已实现范围。

---

## 一、为什么要做

如果 aibi 的能力只在本系统 UI 内可达，就会错过三类真实诉求：

- 业务同学在**自己的 AI 助手里**顺手问一句"上个月的销售额"，不想再切到 aibi；
- 把 aibi 作为**企业 Agent 的一个数据源工具**，与工单、文档等工具一起被编排；
- 客户希望把问数能力**嵌进自己已有的智能体**，而不暴露底层数据库。

MCP（Model Context Protocol）正是为此而生的开放协议——它相当于"AI 时代的 USB-C"，一次接入、所有兼容客户端可用。**不做 MCP，就等于把问数能力锁在自己的页面里。**

---

## 二、规范要点（截至本仓库当前版本）

| 维度 | 现状 | 对我们的含义 |
| --- | --- | --- |
| 传输 | **Streamable HTTP** 为远程默认：单端点 `POST /mcp`，响应可为单条 JSON 或 SSE 流 | 用 `/mcp` 单端点即可；SSE 用于长任务实时进度回传 |
| 会话 | **stateless core**：不再有 `initialize` 握手与 `Mcp-Session-Id`，每个请求自带版本与身份 | 可直接跑在普通轮询负载均衡后，**不需要 sticky session**——与本项目无状态 JWT 体系天然契合 |
| 鉴权 | 面向互联网的 MCP Server 必须 **OAuth 2.1 + PKCE(S256)**；Server 是**资源服务器**（只校验令牌，不签发） | 我们不自己造登录，令牌由外部 IdP / 授权服务签发；本仓库另提供 PAT 做闭环 |
| 发现 | RFC 9728 `/.well-known/oauth-protected-resource` + RFC 8414 `/.well-known/oauth-authorization-server` | 两个 well-known 端点必须暴露 |
| 令牌绑定 | **RFC 8707** resource 参数把令牌绑定到本服务 URI，**禁止 token passthrough** | 防止"给 A 服务的令牌被拿来调 B 服务"的混淆代理攻击 |
| 原语 | tools / resources / prompts；`tools/list` 结果可缓存 | 问数做成 tool，指标/字典做成 resource；prompts 属可选后期能力 |
| 版本 | 客户端在 `initialize` 报什么版本，stateless 服务端应能兼容 | 本项目实现"客户端报哪个就回哪个"，兼容新旧 SDK |

> 一句话：**这是一个已经标准化、有 SDK、客户端生态成熟的协议，不是我们要自创的东西。**

---

## 三、能力映射：aibi 对外暴露什么

原则：**对外暴露"意图级"能力，而不是"SQL 级"能力。** 让模型调用"问一个问题"，而不是"写一条 SQL"。

### 3.1 Tools（可被模型主动调用，均已实现）

| Tool | 入参 | 说明 | 权限点 |
| --- | --- | --- | --- |
| `ask_data` | `question`（必填）、`datasource_id`（可选）、`max_rows`（可选） | 核心能力：自然语言问数，返回结构化结果 + AI 结论 + 质量分。**复用现有问数主链路** | `chat:query:execute` |
| `list_datasources` | — | 列出当前令牌可见的数据源，供模型先定位再提问 | `datasource:config:read` |
| `list_metrics` | `keyword?` | 列出已沉淀指标（含中文名与口径），鼓励复用已验证口径 | `metric:item:read` |
| `refresh_metric` | `metric_id` | 刷新指标取最新值（确定性、快、口径稳定） | `metric:item:refresh` |

> 刻意**不提供** `run_sql` 这类裸 SQL 工具：把用户输入拼进 SQL 是 MCP 最典型的安全事故，且会绕过我们的只读校验、脱敏与配额。

**清单按调用者真实角色动态裁剪**：没权限的工具不出现在 `tools/list` 结果里，避免模型看到（并尝试调用）它本不该用的能力。

### 3.2 Resources（可被客户端读取的上下文，均已实现）

- `datasource://{id}/schema`：表/字段与业务别名（语义层），帮助模型写对问题；
- `metric://{id}`：单个指标的定义、口径与最近结果；
- `aibi://faq`：常见问法示例。

`resources/templates/list` 声明上述前两种参数化 URI 模板，便于严格客户端发现。

### 3.3 Prompts（声明能力但未落地）

`initialize` 声明 `capabilities.prompts = {}`，`prompts/list` 返回空数组。未来接入"AI 数据解读"模板时可直接扩展 `prompts/get`，不必改协议握手。

---

## 四、架构

```
外部 MCP 客户端
   │  POST /mcp  (Authorization: Bearer <PAT or IdP JWT>, Accept: application/json | text/event-stream)
   ▼
aibi MCP 端点（module/mcp）
   ├─ 1. McpTokenResolver 校验凭据（PAT 哈希查库 / 外部 IdP JWT via JWKS / 本系统 JWT）
   ├─ 2. 令牌 → 租户 + 用户 + 角色 映射（复用既有身份体系，无 MCP 专用超级权限）
   ├─ 3. 合成 AccessContext（与嵌入/公开访问同构）
   ├─ 4. McpRateLimiter 双层限流（用户 + 租户，速率按套餐分级 FREE/PRO/ENT）
   ├─ 5. 分派 JSON-RPC：initialize / tools/list / tools/call / resources/list / resources/read /
   │       resources/templates/list / prompts/list / completion/complete / ping
   ├─ 6. 业务侧调 ChatService / MetricService / DataSourceService（现有代码一行不改）
   └─ 7. 每次 tools/call 写审计（工具、身份主体、租户、问题摘要）
```

关键点：**MCP 层只做协议适配与鉴权，业务能力全部复用现有 Service。** 这样"问数准确率""脱敏""配额"等既有治理自动生效，不会出现 MCP 成为绕过治理的旁路。

### 4.1 令牌与身份映射（最重要的一节）

外部拿到的是 PAT 或 IdP 签发的访问令牌，aibi 需要把它映射成本系统的 `AccessContext`：

1. **PAT**（`aibi_pat_*`）：SHA-256 哈希查 `mcp_pat` 表；吊销立即生效（每请求重读，无缓存）；每次调用刷新 `last_used_at`；
2. **外部 IdP JWT**（RS256 via JWKS）：校验签名 → `exp`（RFC 7519）→ `iss`（RFC 9207）→ `aud` / `resource`（RFC 8707）；
3. **本系统 JWT**（HS256，配置允许时）：直接复用登录态；
4. 按令牌里的 `uid` 或 `sub` 找到 aibi 用户；账号停用/不存在立即拒；
5. 以该用户的**真实角色集合**构造 `AccessContext`——**不做"MCP 专用超级权限"**；
6. 之后一切照旧：权限点校验、行级范围、字段脱敏、配额扣减、审计留痕。

这样 MCP 调用与本系统内的问数**安全语义完全一致**。

### 4.2 传输协商

- 客户端 `Accept: text/event-stream` → 走 SSE 流：`notifications/progress` 实时下发引擎 15 个阶段的百分比 + 15s 心跳防代理空闲掐断 + `X-Accel-Buffering: no` 关 nginx 缓冲；
- 其他情况走 JSON 单响应；批处理数组按 JSON-RPC 2.0 §6 逐条处理；
- GET / DELETE `/mcp` → **405 + Allow: POST**（无状态实现不提供 SSE 下行流与会话终止；否则部分客户端握手探测会被 500 兜住误判服务不可用）。

### 4.3 协议版本兼容

`initialize` 里"客户端报什么版本就原样回显"——stateless core 对版本零依赖，兼容旧 SDK（`2025-06-18` / `2025-03-26` / `2024-11-05`）与新 SDK。未传版本时用本服务最新版兜底。

### 4.4 多租户

- 一个 MCP 端点服务所有租户，租户由令牌决定，不靠路径或子域；
- `tools/list` / `resources/list` 按调用者权限裁剪，无权限的工具/数据源不出现在描述里，避免模型看到不该看的东西。

---

## 五、安全边界（不可妥协）

| 风险 | 约束 |
| --- | --- |
| SQL 注入 / 越权读 | **绝不提供裸 SQL 工具**；所有查询必须经现有只读 SQL 校验与参数化执行 |
| 敏感数据泄露 | 脱敏按调用者真实角色生效，MCP 不豁免；`resources` 同样受权限约束（否则成了"绕过工具鉴权读数据"的侧门） |
| 令牌盗用 | 强制 HTTPS；校验 audience/resource；短 TTL + 可撤销；禁止 token passthrough |
| 配额被刷爆 | MCP 调用**计入问数配额**并单独限流（按用户 + 按租户），速率按套餐分级；Agent 循环很容易把 QPS 打满 |
| 提示注入 | 把外部传入的 `question` 当**数据**处理，不拼接进系统提示词执行；上限 500 字符 |
| 可追溯 | 每次 tool 调用写审计日志（谁、哪个租户、什么问题、命中哪个技能），与现有 `audit_log` 同源 |
| 破坏性操作 | 只做只读能力；写类工具（建指标/改语义）不在本方案范围，若引入需二次确认与组织开关 |
| 缓存泄露 | 响应统一 `Cache-Control: no-store`，防租户数据进共享缓存 |

---

## 六、实施路径与当前落地状态

| 期次 | 任务 | 状态 |
|-----|------|------|
| 一期 | `mcp` 模块 + `POST /mcp` + JSON-RPC 分发 | ✅ |
| 一期 | `tools/list` / `tools/call` + `ask_data` | ✅ |
| 一期 | PAT 鉴权闭环（哈希存储 / 吊销立即生效 / `last_used_at`） | ✅ |
| 一期 | 配额、限流、审计打通 | ✅ |
| 一期 | 前端「MCP 令牌」管理后台（签发/列表/吊销 + 客户端配置教程 + curl 自测） | ✅ |
| 二期 | OAuth 2.1 资源服务器：两个 well-known 端点、RFC 8707 resource 校验 | ✅ |
| 二期 | `list_datasources` / `list_metrics` / `refresh_metric` | ✅ |
| 二期 | Resources：`metric://{id}` / `datasource://{id}/schema` / `aibi://faq` | ✅ |
| 二期 | 客户端一键配置样例（mcp.json 动态拼站点 origin + 令牌） | ✅ |
| 三期 | SSE 流式进度 + 15s 心跳 | ✅ |
| 三期 | `resources/templates/list` / `prompts/list` / `completion/complete` | ✅ 协议占位 |
| 三期 | Prompts 实际内容（AI 数据解读模板） | ⏳ 待接入 |
| 三期 | 写类工具（改指标、改语义层） | ⏳ 待评估 |
| 三期 | 多实例部署：Redis 分布式限流替换进程内固定窗口 | ⏳ 待扩展 |

---

## 七、风险与权衡

- **规范仍在演进**：MCP 每年迭代多个版本。建议只实现**稳定核心**（tools + Streamable HTTP），把鉴权细节隔离在适配层，便于跟随升级——本项目 `McpTokenResolver` 就是这层隔离。
- **成本可控性**：MCP 让调用方从"人"变成"Agent"，调用量可能数量级上升。必须先把配额与限流做好再对外开放，否则模型费用不可控。
- **准确率外溢风险**：外部模型提问质量参差，答非所问会被归咎于 aibi。`ask_data` 返回时带上 `qualityScore`（0~100）与 `answered` 标记，让调用方自行判断"是否真答所问"。
- **不要重复造轮子**：优先用官方 SDK；自实现 JSON-RPC 容易在边界情况（批处理、取消、进度通知）上翻车。本项目走"自实现"路线是为了契合既有 Spring Boot 无状态栈；如下一版本发现维护成本上升，可切官方 Java SDK。
- **豆包 / WorkBuddy / 各类国内客户端 Header 差异**：`Authorization` 头**允许省略 `Bearer ` 前缀**（凭 `aibi_pat_*` 前缀仍能定位类型），已在实际接入中验证。

---

## 八、结论

**核心技术上没有阻塞点**（协议标准化、业务 Service 可直接复用），**真正的难点在治理而非协议**——把配额、限流、审计与身份映射做扎实，MCP 才是资产而不是敞开的口子。

本项目从一期 PAT 闭环到二期 OAuth 2.1 资源服务器、三期 SSE 流式与协议占位扩展，均已按方案落地。部署时参见 [本地启动与联调](../05-operations/本地启动与联调.md) 中的 MCP 端点自检步骤。
