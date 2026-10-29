# aibi · AI-Native BI

> 配置数据源即可「会说话就能做数据分析」的对话式 BI 平台。
> 自然语言问数（NL2SQL）· 多租户 SaaS 隔离 · 多类型数据源接入 · 指标与可视化大屏 · 可嵌入的对话助手 · 对外 MCP 服务。

> 🚀 **在线体验** · <https://iyun.ink/> —— 与仓库同版本的公网 Demo，免注册可直接进入（须知请见下方 [在线体验](#-在线体验) 一节）。

aibi 把大模型与数据库查询安全地结合起来：用户用自然语言提问，系统在受控、只读、可审计的链路里生成并执行 SQL，返回结果与推荐图表；同时提供指标管理、可视化大屏、定时报表、分享与嵌入、以及面向 Agent 的 MCP 能力。

---

## ✨ 核心能力

- **对话式问数（NL2SQL）**：自然语言 → SQL → 结果 + 图表推荐；SSE 流式返回，支持多轮对话与查询历史。
- **安全红线**：NL 生成的 SQL 经只读校验、并发护栏与配额限流；数据源凭据 AES-GCM 加密存储，全链路 fail-soft。
- **多租户 SaaS**：租户 / 用户 / 角色权限 / 组织协同，行级与库级多种隔离模式。
- **多数据源接入**：MySQL / PostgreSQL / SQLite 内置，冷驱动（SQL Server / Oracle / ClickHouse / Hive / Trino / Presto）插件化按需加载；支持 CSV / Excel 本地上传。
- **指标与大屏**：指标定义、去重与收藏；可视化大屏（柱/线/饼/雷达/漏斗/仪表盘/地图等）、主题体系、公开实时分享。
- **定时报表与消息**：支持动态相对时间变量的定时报表、站内信与短信/邮件通知。
- **可嵌入与对外 MCP**：一行 `<iframe>` 免登录嵌入对话；对外 MCP 服务供 Agent 调用。

## 🧱 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2 · Java 17 · MyBatis-Plus · JWT · SSE |
| 前端 | Vue 3 · Vite · TypeScript · Element Plus · ECharts |
| 元数据库 | MySQL 5.7+ / 8.x（平台元数据 `aibi_meta`） |
| 大模型 | DeepSeek（主力，OpenAI 兼容）· 通义千问 Qwen（降级备选）· 可插拔 |

后端为进程内实现缓存 / 配额 / 并发护栏（适配单机 4C4G、零外部中间件依赖），生产多实例部署时提供 Redis 平替路径。

## 📁 目录结构

```
.
├── backend/            # Spring Boot 后端
│   ├── src/main/       # 源码（common 基础设施 + module 业务模块）
│   ├── pom.xml
│   ├── Dockerfile
│   └── local.env.example   # 本地凭据模板（复制为 local.env 使用）
├── frontend/           # Vue3 + Vite 前端
├── docs/               # 产品 / 架构设计文档
├── poc/                # NL2SQL 早期可行性验证
└── scripts/            # 全栈本地初始化引导（init.ps1 / init.sh）
```

## 🎯 在线体验

👉 **<https://iyun.ink/>** —— 与仓库同版本的公网 Demo，免搭建即可体验：

- **对话式问数（NL2SQL）**：预置示例销售 / 订单数据源，输入自然语言即可查看生成过程、SQL 溯源与推荐图表；
- **指标与可视化大屏**：驾驶舱、指标收藏、分享链接；
- **可嵌入对话**：iframe 一行代码验证嵌入链路（需 Embed Token）。

**须知**

- Demo 数据为公共示例，会**不定期重置**；
- **切勿输入真实凭据 / 业务敏感信息 / 个人信息**（数据源密码、手机号、邮箱等）；
- 公共资源带宽与算力有限，问数响应可能比本地部署略慢；
- 生产部署请参照下方「快速开始」与 [SECURITY.md](SECURITY.md) 自行加固。

## 🚀 快速开始

### 环境要求
- JDK 17+，Maven 3.8+
- Node.js 18+
- MySQL 5.7+ / 8.x
- 一个可用的 LLM API Key（DeepSeek 或 通义千问）

### 一键引导（推荐）

```powershell
# Windows
pwsh scripts/init.ps1
```

```bash
# Linux / macOS
bash scripts/init.sh
```

引导脚本会交互式收集必需的凭据并写入本地 `backend/local.env`，完成数据库初始化与前后端启动。

### 手动启动

**0) 用 Docker 一键起（可选）**：填好 `backend/local.env` 后：

```bash
cd backend && mvn -B -DskipTests package && cd ..
docker compose up -d
# 访问 http://localhost/
```

**1) 配置凭据**：复制 `backend/local.env.example` 为 `backend/local.env`，填写以下环境变量（均无真实默认值，缺失时启动会快速失败并给出中文指引）：

```
AIBI_DB_URL=jdbc:mysql://127.0.0.1:3306/aibi_meta?createDatabaseIfNotExist=true&...
AIBI_DB_USERNAME=...
AIBI_DB_PASSWORD=...
JWT_SECRET=<≥32 字节强随机>
AIBI_CIPHER_KEY=<数据源凭据 AES-GCM 主密钥>
DEEPSEEK_API_KEY=<你的 DeepSeek Key>
```

**2) 启动后端**：

```bash
cd backend
mvn -B spring-boot:run
```

**3) 启动前端**：

```bash
cd frontend
npm install
npm run dev
```

默认开发登录口令见 `application.yml`（`superadmin` / `admin` 使用内置默认，生产务必经环境变量覆盖）。

## 🔒 安全说明

- 所有真实凭据（数据库、JWT 主密钥、加密主密钥、LLM Key、SMTP、短信）**只经环境变量注入**，仓库内不落任何明文。
- NL 生成的 SQL 走只读校验与手术式处理边界，绝不执行写接口；并发与配额双重护栏。
- 数据源凭据以 AES-GCM 加密存储，主密钥与库分离。
- 生产部署建议：`systemd EnvironmentFile=/opt/aibi/aibi.env`（权限 `600`）。

## 📖 文档

- [文档索引](docs/README.md)
- 产品定位：[docs/01-product/](docs/01-product/)
- 架构设计：[docs/02-architecture/](docs/02-architecture/)（前后端架构 / 多租户与数据源接入 / 角色权限与组织协同）
- 专题方案：[docs/04-analysis/对外MCP能力方案.md](docs/04-analysis/对外MCP能力方案.md)
- 运维交付：[docs/05-operations/本地启动与联调.md](docs/05-operations/本地启动与联调.md) · [docs/05-operations/用户操作手册.md](docs/05-operations/用户操作手册.md)
- 学习指南：[docs/06-learning/](docs/06-learning/)

## 🤝 贡献

欢迎提交 Issue 与 Pull Request。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
提交前本地自检：后端 `mvn -B test` 通过，前端 `npm run build`（含 `vue-tsc` 类型检查）通过。

## 🔐 安全与漏洞披露

发现安全问题请通过 [SECURITY.md](SECURITY.md) 描述的私密渠道报告，**不要**开公开 Issue。

## 📄 许可

本项目基于 [Apache License 2.0](LICENSE) 开源。变更历史见 [CHANGELOG.md](CHANGELOG.md)。
