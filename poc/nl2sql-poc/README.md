# NL2SQL PoC（P0 技术验证）

最小可运行验证：**Java 连 MySQL + 调 DeepSeek 生成 SQL + JSqlParser 安全校验 + 只读执行**。
对应《开发计划文档》任务 **T0.3 / T0.4 / T0.6**，是决定项目是否继续的 **G0 门禁** 验证。

## 目录结构

```
poc/nl2sql-poc/
├── pom.xml                                  # JDK17 + mysql-connector-j + jsqlparser + jackson
├── sql/sample_schema.sql                    # 示例电商库 aibi_demo（建表+样本数据+测试问题）
└── src/main/java/com/aibi/poc/
    ├── Nl2SqlPoc.java                       # 主程序：读表结构→Prompt→执行 编排
    ├── DeepSeekClient.java                  # DeepSeek Chat Completions 客户端
    └── SqlGuard.java                        # JSqlParser 校验：仅 SELECT + 强制 LIMIT
```

## 前置条件

| 项 | 要求 |
|----|------|
| JDK | 17+（本机 22 亦可，pom 以 release=17 编译） |
| Maven | 3.9+（未装可用 IntelliJ 内置 Maven 运行 `Nl2SqlPoc` 主类） |
| MySQL | 本地 8.x，可 root 登录 |
| LLM | DeepSeek API Key（[platform.deepseek.com](https://platform.deepseek.com)） |

## 运行步骤

**1. 建库导数据**

```bash
mysql -uroot -p < sql/sample_schema.sql
```

**2. 设置环境变量**

```powershell
# PowerShell
$env:DEEPSEEK_API_KEY = "sk-xxxxxxxx"
$env:DB_USER = "root"
$env:DB_PASS = "你的MySQL密码"
# 可选：$env:DB_URL / $env:DB_SCHEMA / $env:DEEPSEEK_MODEL
```

```bash
# Linux/macOS
export DEEPSEEK_API_KEY="sk-xxxxxxxx"
export DB_USER="root"
export DB_PASS="你的MySQL密码"
```

**3. 运行**

```bash
mvn -q compile exec:java -Dexec.args="2026年8月每个地区的已支付订单总金额是多少"
```

> 不带参数则使用内置默认问题。也可直接在 IDE 中运行 `Nl2SqlPoc.main()`，用程序参数传问题。

**预期输出**：依次打印 `表结构 → LLM 原始输出 → 校验后 SQL → 查询结果`。

## G0 门禁验证清单

- [ ] **端到端跑通**：能从自然语言得到正确结果
- [ ] **准确率 ≥70%**：用 `sql/sample_schema.sql` 末尾的测试问题 + 自建共 20 问，人工判定
- [ ] **单次 LLM 成本 ≤¥0.01**：看 DeepSeek 返回 `usage` 的 token 数估算
- [ ] **安全护栏有效**：手动把 LLM 输出改成 `DELETE ...` / 多语句 / `INTO OUTFILE`，应被 `SqlGuard` 拦截

> 三项全部达标 → 通过 G0，进入 P1 MVP 开发；准确率不达标 → 换/叠加模型、增强 Schema Linking，仍不行则按报告 11.4.2 pivot。

## 与 MVP 骨架的关系

本 PoC 为**一次性验证代码**（无 Spring）。其 `DeepSeekClient`、`SqlGuard` 的核心逻辑已在
`backend/` 骨架中重构为 Spring 组件（`DeepSeekClient`、`SqlValidator`），验证通过后即以骨架为准继续开发。
