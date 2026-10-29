# 安全策略（Security Policy）

aibi 是一款**面向企业数据资产**的对话式 BI 平台：NL2SQL 引擎把自然语言转成 SQL 并在业务库上执行；MCP 端点把问数能力开放给外部 AI 客户端；数据源凭据以 AES-GCM 加密存储。因此我们把安全视为**第一优先级**，任何可能绕过只读红线、脱敏、配额或审计的问题都按最高优先级处理。

---

## 报告一个漏洞

**请勿通过公开 Issue 报告任何安全相关问题。**

请通过以下渠道之一向我们私下披露：

1. **GitHub 私密漏洞报告**：仓库 → Security → "Report a vulnerability"（推荐，走 GitHub 私密协商通道）。
2. **邮件**：`security@aibi.example.com`（占位；实际部署方请替换为本组织的运营邮箱）。
   - 若可能，请用我们的 PGP 公钥加密（发布在仓库 releases 页）。

**报告内容请尽量包含**：

- 影响版本（`git log -1 --format=%H` 或 `mvn -v` 打出的版本号）
- 复现步骤（HTTP 请求 / 客户端配置 / SQL 载荷 / MCP tools/call body 任一）
- 影响评估（越权读 / SQL 注入 / 凭据泄露 / 拒绝服务 / 其他）
- 建议的修复方向（可选）

**响应承诺**：48 小时内确认收到；7 天内给出初步评估；30 天内给出修复计划或临时缓解措施。修复公开前，请给我们合理的披露窗口。

---

## 我们已内建的安全红线

以下是设计上"绝不容许被绕过"的边界，任何能突破它们的构造都视为漏洞：

### 1. NL2SQL 只读红线

- 所有 LLM 生成的 SQL 经 **JSqlParser 静态分析**，仅允许 SELECT；任何 DDL / DML / `CALL` / `INTO OUTFILE` / 多语句一律拒绝。
- 手术式改写追加行级过滤（如 `tenant_id = ?`），确保不跨租户读取。
- 具体边界见 `docs/02-architecture/前后端架构设计.md` 与后端 `SqlGuard` / `ConcurrencyGuard` 实现。

### 2. 凭据治理

- `application.yml` 无任何真实默认值；`SecretsGuardEnvironmentPostProcessor` 启动时**缺项即 fail-fast 退出**。
- 数据源凭据以 **AES-GCM** 加密存库，主密钥 `AIBI_CIPHER_KEY` 独立于数据库、经环境变量注入；即便元数据库泄露也无法解密业务库凭据。
- MCP 个人访问令牌（PAT）库里**只存 SHA-256 哈希**，明文只在签发瞬间显示一次，不可再还原。
- **仓库不落任何明文**：所有真实凭据经环境变量注入；CI 有凭据扫描门禁（正则 + 熵检测）。

### 3. MCP 端点

- `/mcp` **不经 Spring Security**：每请求自带 PAT 或外部 IdP 签发的 JWT，由 `McpTokenResolver` 独立鉴权。
- 严格实现 RFC 8707 audience 校验：给别的服务签的令牌拿到本服务用会被拒（防混淆代理攻击）。
- **不提供 `run_sql` 等裸 SQL 工具**：所有工具都是"意图级"（`ask_data` / `list_metrics` 等），复用网页端同一套权限点、脱敏与配额。
- 每次 `tools/call` 写审计（工具、身份主体、租户、问题摘要）；按用户 + 按租户双层限流，速率档随套餐分级（FREE/PRO/ENT）。

### 4. 权限与脱敏

- 权限点通过 `@RequiresPermission` 注解 + `PermissionAspect` 统一拦截，`ApiPermissionCoverageTest` 门禁扫描所有控制器，未挂权限点必须走"有意不挂"白名单并逐条给出理由。
- 字段级脱敏、行级数据范围在 MyBatis 层通过 `AibiDataScopeHandler` 与 `DataScopeRegistrar` 织入，MCP / 嵌入 / 公开分享同样受约束。
- 无"MCP 专用超级权限"：MCP 调用者权限完全等同其网页端角色。

### 5. 传输与运行时

- 生产部署必须走 HTTPS；`Cache-Control: no-store` 阻止 MCP / 分享响应进入共享缓存。
- 会话 JWT：HS256（本系统）+ RS256（外部 IdP via JWKS），过期即失效；停用用户 / 改密后所有旧凭据立即不可用。
- 密码修改、PAT 吊销、成员移除等操作**无缓存层**，每请求重读库。

---

## 支持版本

| 版本 | 是否支持安全更新 |
|------|------------------|
| `main` 分支最新提交 | ✅ |
| 最近 2 个 tag 发布版 | ✅ |
| 更早版本 | ❌（请升级后复现） |

---

## 加固建议（部署侧）

以下动作能显著降低运维风险：

1. **HTTPS 终结**：`systemd` + nginx 反代，`X-Forwarded-Proto` 传到后端；HSTS 至少 1 年。
2. **凭据文件权限**：`/opt/aibi/aibi.env` 权限 `600`，owner 仅部署用户；不进任何镜像层。
3. **主密钥轮换**：`AIBI_CIPHER_KEY` 变更前需先做双 key 重加密（见 `docs/03-development` 内 T1 凭据轮换清单；开源版可自行实现）。
4. **MCP 令牌**：给外部客户端签发的 PAT 建议设 30~90 天有效期，绑定专用集成账号；离职 / 集成下线时立即吊销。
5. **数据库最小权限**：aibi 元数据库连接账号只给 `CREATE/DROP DATABASE aibi_meta` + DML；**业务数据源账号只给 `SELECT`**（我们只读红线的第一道外部护栏）。
6. **审计日志外发**：`audit_log` 建议异步同步到 SIEM / WORM 存储，防运维删表。
7. **限流阈值**：默认 `AIBI_MCP_RL_USER=30` / `AIBI_MCP_RL_TENANT=120` 次/分钟；对外公网部署建议进一步收紧。

---

## 安全响应流程（披露政策）

我们对**善意披露**的研究者承诺：

- 不因你按本报告要求做的最小复现行为发起法律追诉；
- 修复 CVE 公开发布时，如你希望，会在 Release Notes / Advisories 中**署名致谢**；
- 严重漏洞可协商合理赏金（视本组织运营方政策）。

请勿：

- 对生产实例做 DoS / 社会工程 / 物理入侵；
- 拉取或修改真实用户数据（可使用自己的测试账号）；
- 在未修复前公开披露细节（协同披露窗口默认 90 天）。

---

## 相关文档

- [对外 MCP 能力方案](docs/04-analysis/对外MCP能力方案.md)
- [角色权限体系与组织协同设计](docs/02-architecture/角色权限体系与组织协同设计.md)
- [本地启动与联调](docs/05-operations/本地启动与联调.md)
- [贡献指南](CONTRIBUTING.md)
