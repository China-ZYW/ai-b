<!--
  感谢提交 PR！请遵循 CONTRIBUTING.md。
  安全相关变更请勿在此公开描述，先走 SECURITY.md 私密披露渠道。
-->

## 一句话说明 / What & Why

<!-- 修复什么问题 / 增加什么能力；关联 Issue 用 `Closes #123` -->

Closes #

## 变更清单 / Changes proposed

- [ ] 后端改动（列出模块/类）
- [ ] 前端改动（列出视图/组件）
- [ ] 数据库变更（附迁移 SQL，且向后兼容）
- [ ] MCP 协议能力变更（附影响的 method）
- [ ] 文档更新
- [ ] 其它：

## 如何验证 / How to test

1. 后端：`cd backend && mvn -B test`
2. 前端：`cd frontend && npm run build`（含 vue-tsc 类型检查）
3. 手工验证步骤（若涉及 UI / SSE / MCP）：
   - 步骤 A → 期望 X
   - 步骤 B → 期望 Y

## 自检清单 / Self-review checklist

- [ ] 本地 `mvn -B test` 全绿
- [ ] 本地 `npm run build` 全绿（含 vue-tsc）
- [ ] Commit message 遵循 Conventional Commits
- [ ] 已 rebase 到最新 `main`，无冲突
- [ ] **无**本机绝对路径 / 用户名 / 内网 IP 泄露
- [ ] **无**明文密钥（DB 密码 / JWT / AES 主密钥 / LLM Key / SMTP 授权码）
- [ ] 涉及 SQL 执行链路时，保留只读校验与并发护栏（不可绕过）
- [ ] 涉及 MCP：`tools/call` 走 Token→tenant 身份映射，不越权
- [ ] 涉及配置项：`application.yml` 与 `backend/local.env.example` 同步

## 风险与回滚 / Risk & rollback

<!-- 影响面、灰度策略、回滚方式；无风险可写 "无" -->

## 截图 / 录屏（可选）

<!-- UI 变更建议附前后对比 -->
