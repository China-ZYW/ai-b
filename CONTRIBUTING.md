# 贡献指南（Contributing Guide）

感谢你有兴趣让 aibi 变得更好。以下约定能让我们 review 更快、合并更稳。

## 快速开始

1. **Fork + Clone**：把仓库 Fork 到你的组织，clone 到本地。
2. **配好本地环境**：按 [docs/05-operations/本地启动与联调.md](docs/05-operations/本地启动与联调.md) 跑通前后端。
3. **建分支**：`git checkout -b feat/<简短主题>` 或 `fix/<issue号>-<主题>`。
4. **提交**：遵循 Conventional Commits（见下）。
5. **发起 PR**：填写 PR 模板，勾选自检清单。

## 提交规范（Conventional Commits）

格式：`<type>(<scope>): <subject>`

常用 type：

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `perf` | 性能优化 |
| `refactor` | 重构（不改行为） |
| `docs` | 文档 |
| `test` | 测试 |
| `build` | 构建系统或依赖 |
| `ci` | CI 配置 |
| `chore` | 其他杂项 |
| `revert` | 回滚 |

`scope` 建议按模块：`chat`、`datasource`、`mcp`、`rbac`、`dashboard`、`metrics`、`security`、`frontend`、`docs`、`build` 等。

示例：

```
feat(mcp): 补 resources/templates/list 协议占位
fix(chat): 多意图拆分不再嵌套抢 ConcurrencyGuard 许可
docs(security): 增补 MCP 令牌轮换与最小权限建议
```

**中文提交信息完全允许**（本项目历史全部为中文）；文件内容 UTF-8 保存，Windows PowerShell 脚本必须 UTF-8 带 BOM。

## 代码规范

### 后端（Java / Spring Boot）

- **Java 17+**；不引入 Kotlin / Scala；
- 一个 `.java` 文件**只允许一个 public 类型**，且文件名与 public 类名一致；
- 所有业务控制器方法必须挂 `@RequiresPermission` 权限点；若"有意不挂"，需加进 `ApiPermissionCoverageTest.INTENTIONALLY_OPEN` 白名单**并写明理由**（否则 CI 门禁失败）；
- 涉及凭据的字段（口令 / API Key / 令牌明文）**绝不允许**在日志、审计、异常消息里出现明文；
- **禁止** `List.of(null)` / `Set.of(null)` / `Map.of(null, ...)`（Java 集合对 null 参数抛 NPE）；
- `@Transactional` 方法**避免**内部 `try { ... } catch (Exception e) { log.warn(...) }` 单一兜底：这会吞掉 MyBatis 异常导致部分写不落库且无告警。分类捕获或让异常上抛由事务边界回滚；
- 中文注释允许，术语保留英文原形（SQL / JWT / SSE 等不翻译）。

### 前端（Vue 3 / TypeScript / Vite）

- Vue 组件用 `<script setup lang="ts">`；
- 类型检查：`npm run build` 内含 `vue-tsc` 类型检查；PR 前必须通过；
- **单文件不超过 ~400 行**；巨型视图拆分按 `provide/inject reactive 聚合` 模式（参考已有 `AppChatView` 拆分方案）；
- Vue 模板里显示 `{{token}}` 字面量注意 mustache 截断，用常量渲染配方；
- Vite 代理 Windows 下必须显式指 IPv4 `http://127.0.0.1:8080`（不能用 `localhost`，否则被解析到 `::1` 导致 500 空响应）。

### 通用

- **不使用** emoji 装饰代码或日志（README 与文档可用）；
- **零明文原则**：任何密钥、口令、Token 明文一律不落仓库；PR 里发现凭据会被直接拒；
- **保守开发**：接口设计优先只读，写接口需明确幂等边界与审计落点。

## 测试

- 后端：`mvn -B test` 必须全绿；新增功能应附**纯逻辑单测**（不起 Spring、不连库、mock 依赖）；
- 前端：`npm run build` 必须通过（含 vue-tsc 类型检查）；
- 集成测试目前以 shell/ps1 脚本形式归档到 `backend/api-smoke.ps1` / `backend/feature-smoke.ps1`；新增业务接口应同步扩测。

## 变更范围建议

**优先做的**：
- Bug 修复（附最小复现步骤）
- 文档改进（README / docs / JSDoc / JavaDoc）
- 小型功能扩展（单模块内 <200 行改动）
- 测试覆盖补齐

**先讨论再做的**：
- 修改 NL2SQL 主链路或安全红线（只读校验、脱敏、审计）
- 修改 MCP 协议分派或鉴权语义
- 引入新依赖（尤其是 Python / Node 生态的大依赖）
- 数据库 schema 迁移（`SchemaMigration` 加 step）

**不做**：
- 破坏 API 兼容性的字段删除（走"新增 → 弃用 → 删除"三段）
- 裸 SQL 工具或 MCP 写类工具（详见 [对外 MCP 能力方案](docs/04-analysis/对外MCP能力方案.md) 五、安全边界）

## PR 自检清单

打开 PR 前请勾选：

- [ ] `mvn -B test` 后端全绿
- [ ] `npm run build` 前端全绿（含 vue-tsc）
- [ ] 无凭据 / 密钥 / 令牌明文出现
- [ ] 提交历史已 rebase 干净（可选 squash）
- [ ] commit message 遵循 Conventional Commits
- [ ] 新增/修改功能已同步更新相关文档
- [ ] 新增/修改控制器方法已挂权限点或加白名单

## 报告 Issue

- Bug：使用 "Bug report" 模板，含版本、复现步骤、期望 vs 实际、日志片段
- 安全漏洞：**不要开公开 Issue**，请按 [SECURITY.md](SECURITY.md) 私下披露

## 行为准则

本项目采用 [Contributor Covenant 1.4](CODE_OF_CONDUCT.md)。参与者需遵守。

## 许可

在你提交 PR 时即同意：你的贡献以 Apache License 2.0 授权给本项目。详见 [LICENSE](LICENSE)。
