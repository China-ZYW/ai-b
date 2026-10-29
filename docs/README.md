# aibi 文档索引

> 代号 **aibi**（AI 数据助理 / AI-Native BI）：配置数据源即可「会说话就能做数据分析」的对话式 BI 平台。
> 本目录收录产品与架构设计类文档，帮助理解系统「应该是什么样」以及如何把它跑起来。

## 一、按主题速查

| 我想… | 去哪里 |
|-------|--------|
| 了解产品定位与整体设计思路 | [01-product/可行性研究报告.md](01-product/可行性研究报告.md) |
| 上手开发、知道代码怎么组织 | [02-architecture/前后端架构设计.md](02-architecture/前后端架构设计.md) |
| 搞清楚多租户怎么隔离、数据源怎么接 | [02-architecture/SaaS多租户与数据源接入设计.md](02-architecture/SaaS多租户与数据源接入设计.md) |
| 搞清楚角色 / 权限 / 组织怎么设计 | [02-architecture/角色权限体系与组织协同设计.md](02-architecture/角色权限体系与组织协同设计.md) |
| 把项目在本机 / Docker 里跑起来联调 | [05-operations/本地启动与联调.md](05-operations/本地启动与联调.md) |
| 交付 / 培训终端用户如何操作 | [05-operations/用户操作手册.md](05-operations/用户操作手册.md) |
| 弄清对外 MCP 的能力边界与落地状态 | [04-analysis/对外MCP能力方案.md](04-analysis/对外MCP能力方案.md) |
| 以本项目作为 AI 辅助开发的学习参考 | [06-learning/AI练手项目指南.md](06-learning/AI练手项目指南.md) |

## 二、目录结构

```
docs/
├── 01-product/       产品层：定位与设计思路
├── 02-architecture/  架构层：总体架构、多租户、权限体系（设计态）
├── 04-analysis/      专题方案：对外 MCP 能力、跨模块权衡
├── 05-operations/    运维与交付：本地启动、用户操作
└── 06-learning/      学习与方法沉淀
```

## 三、根级配套文档

| 文件 | 用途 |
|------|------|
| [../README.md](../README.md) | 项目总览与快速开始 |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献指南、代码规范、PR 流程 |
| [../SECURITY.md](../SECURITY.md) | 漏洞披露渠道与部署安全红线 |
| [../CHANGELOG.md](../CHANGELOG.md) | 变更日志（Keep a Changelog） |
| [../CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | 社区行为准则 |

> Docker 一键起：参见根目录 [../docker-compose.yml](../docker-compose.yml)。
