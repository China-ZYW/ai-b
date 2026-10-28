# AI 数据助理 · SaaS 多租户与数据源接入设计

> 本文档是《前后端架构设计文档》的专项补充，聚焦三个能力：**① SaaS 多租户**、**② 分层混合数据隔离**、**③ 多类型数据源接入**。落地形态仍为模块化单体，适配自有 4 核 4G。代码骨架见 `backend/`，落地映射见第 11 章。

---

## 1. 背景与目标

平台从"单库问数 Demo"升级为"多租户 SaaS"，需同时满足：

| 目标 | 要求 |
| --- | --- |
| SaaS 化 | 一套部署服务成千上万租户，租户自助注册、连接自己的数据源、独立问数与看板 |
| 数据隔离 | 租户之间**元数据严格隔离**，任何跨租户读写都必须被架构层阻断，而非依赖开发自觉 |
| 数据源广度 | 接入**主流关系型 + OLAP/数仓 + 文件类**数据库，新增类型的成本可控 |
| 成本可控 | 免费/专业版低成本共享，企业版/私有化强隔离，隔离强度随套餐分层 |

**关键认知**：用户的**业务数据本就存放在其自有数据库中**（天然物理隔离）。平台需要隔离的是**自身存储的元数据**——租户、用户、数据源连接配置（含加密凭证）、查询历史、看板、报告。因此隔离设计围绕"元数据 + 连接路由 + 凭证"展开。

---

## 2. SaaS 多租户总体架构

```
                          ┌────────────────────────────┐
     用户请求 (JWT)  ───▶  │  JwtAuthFilter             │
                          │  解析 uid + tenantId + plan │
                          └──────────────┬─────────────┘
                                         ▼
                          ┌────────────────────────────┐
                          │  TenantContext (ThreadLocal)│  ← 全链路租户身份
                          └──────────────┬─────────────┘
                    ┌────────────────────┼─────────────────────┐
                    ▼                    ▼                     ▼
        ┌───────────────────┐ ┌────────────────────┐ ┌──────────────────────┐
        │ 元数据访问         │ │ 数据源路由          │ │ 配额/限流/计费        │
        │ (MyBatis-Plus      │ │ (TenantDataSource  │ │ (按 tenantId 计数)   │
        │  TenantLineHandler)│ │  Manager)          │ │                      │
        │ 自动注入 tenant_id │ │ 按租户+数据源建池   │ │                      │
        └─────────┬─────────┘ └─────────┬──────────┘ └──────────────────────┘
                  ▼                     ▼
        ┌───────────────────┐ ┌────────────────────────────────────────┐
        │ 元数据库 aibi_meta │ │  租户业务库（用户自有 / 平台受管）        │
        │ tenant/data_source │ │  MySQL · PG · ClickHouse · Doris · CSV… │
        │ /query_history …   │ │  只读账号连接，适配器统一执行            │
        └───────────────────┘ └────────────────────────────────────────┘
```

**租户身份来源**：登录后 JWT 携带 `uid`、`tid`(tenantId)、`plan`。过滤器解析后写入 `TenantContext`，请求结束即清理，杜绝线程复用导致的串租户。

---

## 3. 分层混合数据隔离模型

隔离强度与成本正相关。平台按套餐分层采用不同模型：

| 隔离模型 | 说明 | 隔离强度 | 成本 | 适用套餐 |
| --- | --- | --- | --- | --- |
| **POOL（共享库 + tenant_id）** | 所有租户元数据同库同表，行级 `tenant_id` 区分 | 中（依赖框架强制） | 最低 | 免费版、专业版 |
| **SCHEMA（独立 Schema）** | 同实例内每租户独立 schema/库 | 高 | 中 | 企业版（可选） |
| **DATABASE（独立库/实例）** | 每租户独立数据库甚至独立实例/独立部署 | 最高 | 高 | 企业版、私有化 |

### 3.1 套餐 → 隔离模式映射

```
PlanType        IsolationMode      元数据位置
──────────────────────────────────────────────────────
FREE     ──▶    POOL               aibi_meta（共享，tenant_id 隔离）
PRO      ──▶    POOL               aibi_meta（共享，tenant_id 隔离）
ENTERPRISE ──▶  DATABASE / SCHEMA  独立元数据库 或 独立 schema（可私有化部署）
```

### 3.2 POOL 模式的隔离保证（不依赖开发自觉）

共享库最大的风险是"某条 SQL 忘了加 `WHERE tenant_id=?`"。平台用 **MyBatis-Plus `TenantLineInnerInterceptor`** 在 SQL 执行前**自动改写**，为所有受控表强制追加 `tenant_id` 条件：

```
开发者写:  SELECT * FROM query_history WHERE uid = 100
拦截器改写: SELECT * FROM query_history WHERE uid = 100 AND tenant_id = 7
                                                    ▲ 自动注入，值来自 TenantContext
```

- `TenantLineHandler` 从 `TenantContext` 读取当前租户 id；
- `ignoreTable` 白名单放行全局表（如 `tenant` 自身、字典表）；
- 结合数据库层 **RLS（PostgreSQL 行级安全）或视图**可做双保险（企业版）。

### 3.3 DATABASE/SCHEMA 模式的隔离保证

企业版/私有化下，`TenantContext` 的 `IsolationMode` 驱动**动态数据源路由**：不同租户的元数据访问指向不同的物理库/schema，物理层天然隔离，无需 tenant_id。

---

## 4. 数据隔离的四层防线

| 层 | 隔离对象 | 机制 |
| --- | --- | --- |
| ① 业务数据 | 用户自有数据 | 存放于用户自己的数据库，平台仅**只读**连接，数据不出源（私有化下完全本地） |
| ② 元数据 | 租户/用户/配置/历史 | POOL：MyBatis-Plus 自动注入 tenant_id；DATABASE/SCHEMA：独立物理库 |
| ③ 连接 | 各租户数据源连接池 | `TenantDataSourceManager` 按 `tenantId:datasourceId` 独立建池，互不复用 |
| ④ 凭证 | 数据源账号密码 | AES-GCM 加密落库，密钥独立管理，绝不明文存储/日志 |

---

## 5. 租户上下文传播

```
登录成功
  └─ JwtTokenProvider.generate(uid, username, tenantId, plan)
        └─ JWT claims: { sub, uid, tid, plan, exp }

每次请求
  └─ JwtAuthFilter.doFilterInternal()
        ├─ 解析 claims
        ├─ UserContext.set(uid, username)
        ├─ TenantContext.set(tenantId, planType, isolationMode)   ← 关键
        ├─ SecurityContext.setAuthentication(...)
        └─ finally: UserContext.clear(); TenantContext.clear();   ← 防串租户

业务/数据访问
  ├─ MyBatis-Plus TenantLineHandler.getTenantId() ← 读 TenantContext
  ├─ TenantDataSourceManager.resolve(tenantId, datasourceId) ← 读 TenantContext
  └─ 配额/限流器 ← 以 tenantId 为 key
```

> `TenantContext` 与 `UserContext` 均基于 `ThreadLocal`，异步/线程池场景需显式传递（提供 `TenantContext.wrap(Runnable)` 装饰器）。

---

## 6. 多数据源接入架构

采用**适配器 + 方言 + 注册表**三件套，把"数据库差异"收敛到方言层，把"连接与执行"收敛到适配器层。

```
┌──────────────────────────────────────────────────────────────┐
│  DataSourceType (枚举)                                         │
│  MYSQL / POSTGRESQL / SQLSERVER / ORACLE / CLICKHOUSE /        │
│  DORIS / STARROCKS / HIVE / SQLITE / CSV / EXCEL …             │
│  每个类型携带: family, driverClass, defaultPort, urlTemplate   │
└───────────────┬──────────────────────────────────────────────┘
                ▼
┌──────────────────────────────┐   ┌──────────────────────────────┐
│  SqlDialect (方言接口)        │   │  DataSourceAdapter (适配器)   │
│  - quoteIdentifier()         │   │  - testConnection()          │
│  - buildLimit()              │   │  - scanSchema() → TableMeta  │
│  - schemaQuery()             │   │  - execute(sql) → QueryResult│
│  - typeMapping()             │   │  实现:                        │
│  实现: MySQL/PG/SqlServer/    │   │  - JdbcDataSourceAdapter(通用)│
│  Oracle/ClickHouse/SQLite     │   │  - CsvFileAdapter(文件类)     │
└──────────────────────────────┘   └───────────────┬──────────────┘
                                                    ▼
                              ┌──────────────────────────────────┐
                              │ DataSourceAdapterRegistry         │
                              │ 按 DataSourceType 派发适配器       │
                              └───────────────┬──────────────────┘
                                              ▼
                              ┌──────────────────────────────────┐
                              │ TenantDataSourceManager           │
                              │ 按 tenantId:datasourceId 缓存      │
                              │ HikariCP 只读连接池 + LRU 淘汰      │
                              └──────────────────────────────────┘
```

**设计要点**：
- **通用 JDBC 适配器**：绝大多数库（MySQL/PG/SQLServer/Oracle/ClickHouse/Doris/StarRocks/Hive/SQLite）走同一个 `JdbcDataSourceAdapter`，差异由 `SqlDialect` 承担——新增一种 JDBC 库通常只需加枚举 + 方言。
- **文件类适配器**：CSV/Excel 无 JDBC 协议，`CsvFileAdapter` 将文件解析后载入内存 SQLite 表，再用统一 SQL 查询。
- **连接池按租户隔离**：每个 `(tenantId, datasourceId)` 一个独立 HikariCP 小池（如 max 3），LRU 淘汰空闲池，避免 4GB 下连接数爆炸。

---

## 7. 支持的数据库矩阵

| 类别 | 数据库 | 驱动 | 方言要点 | 骨架状态 |
| --- | --- | --- | --- | --- |
| 关系型 | MySQL / MariaDB | mysql-connector-j | 反引号引用，`LIMIT n` | ✅ 完整 |
| 关系型 | PostgreSQL | postgresql | 双引号引用，`LIMIT n`，可选 RLS | ✅ 完整 |
| 关系型 | SQL Server | mssql-jdbc | 方括号引用，`OFFSET..FETCH`/`TOP` | ✅ 完整 |
| 关系型 | Oracle | ojdbc11 | 双引号引用，`FETCH FIRST n ROWS` | ✅ 完整 |
| 关系型 | SQLite | sqlite-jdbc | 双引号/反引号，`LIMIT n` | ✅ 完整 |
| OLAP | ClickHouse | clickhouse-jdbc | 反引号，`LIMIT n`，弱事务 | ✅ 完整 |
| OLAP | Doris / StarRocks | mysql-connector-j | 兼容 MySQL 协议与方言 | ✅ 复用 MySQL |
| OLAP | Hive / Presto / Trino | 各自 JDBC | `LIMIT n`，无主键/事务弱 | 🔌 类型就绪，驱动按需引入 |
| 文件 | CSV | commons-csv + sqlite-jdbc | 载入内存 SQLite 后查询 | ✅ 骨架 |
| 文件 | Excel | Apache POI + sqlite-jdbc | 同上，解析 xlsx | 🔌 类型就绪，POI 按需引入 |

> "🔌 类型就绪"指枚举与方言已定义，仅需在 `pom.xml` 放开对应驱动依赖即可启用，无需改业务代码。

---

## 8. 方言差异处理

| 差异点 | MySQL | PostgreSQL | SQL Server | Oracle | ClickHouse |
| --- | --- | --- | --- | --- | --- |
| 标识符引用 | `` `col` `` | `"col"` | `[col]` | `"COL"` | `` `col` `` |
| 分页限制 | `LIMIT n` | `LIMIT n` | `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY` | `FETCH FIRST n ROWS ONLY` | `LIMIT n` |
| 元数据查询 | `INFORMATION_SCHEMA.COLUMNS` | 同 + `pg_catalog` | `INFORMATION_SCHEMA.COLUMNS` | `ALL_TAB_COLUMNS` | `system.columns` |
| 当前库函数 | `DATABASE()` | `current_database()` | `DB_NAME()` | `SYS_CONTEXT(...)` | `currentDatabase()` |
| 布尔/类型 | tinyint(1) | boolean | bit | number(1) | UInt8 |

`SqlDialect` 接口把这些差异抽象为方法，`JdbcDataSourceAdapter` 在执行、扫描 schema、强制 LIMIT 时调用对应方言，业务层完全无感知。

---

## 9. 安全设计

| 维度 | 措施 |
| --- | --- |
| 凭证加密 | 数据源密码用 **AES-GCM**（`CredentialCipher`）加密后存 `data_source.config_cipher`；主密钥来自环境变量 `AIBI_CIPHER_KEY`，与库分离 |
| 只读连接 | 适配器执行前 `Connection.setReadOnly(true)`；建议用户仅提供只读账号 |
| SQL 白名单 | 复用 `SqlValidator`：仅 `SELECT`、强制 `LIMIT`、拦截多语句与 `INTO OUTFILE` |
| 超时与行数 | 查询超时 30s、最大 1 万行，按方言注入限制子句 |
| 跨租户阻断 | MyBatis-Plus 自动注入 tenant_id；连接池按租户隔离；上下文 finally 清理 |
| 传输 | 全链路 HTTPS；数据源连接按需启用 SSL |
| 审计 | 查询历史按 tenant_id 落库，含 SQL、耗时、行数、状态 |

---

## 10. 配额、限流与计费

| 维度 | 免费版 | 专业版 | 企业版 |
| --- | --- | --- | --- |
| 隔离模式 | POOL | POOL | DATABASE/SCHEMA |
| 数据源数量 | 1 | 5 | 不限 |
| 每日问数（默认） | 20 | 1000 | -1（不限） |
| 累计总次数 | -1（不限，可配） | -1（不限，可配） | 按合同 |
| 单次扫描行数 | 1 万 | 10 万 | 可配 |
| 并发查询 | 1 | 5 | 专属资源 |

> 表内为建租户时按套餐写入的**默认值**，均落库 `tenant.daily_limit/total_limit`，超管可在后台逐租户改（`-1`=不限）。

- **配额门禁**：`QuotaService.checkAndConsume(tenantId)` 读取 `tenant.daily_limit/total_limit/expire_at`——使用期限到期抛 `TENANT_EXPIRED(40304)`、租户停用抛 `TENANT_DISABLED(40305)`；日计数落 `quota_usage`（`INSERT ... ON DUPLICATE KEY UPDATE used=used+1`，按 `tenant_id+usage_date` 累加）、累计走 `tenant.total_used`，超限回滚并抛 `FREE_LIMIT_EXCEEDED(42901)`（日限）/ `TOTAL_LIMIT_EXCEEDED(42903)`（总限）。配额校验置于并发护栏**之前**，超限不误落历史、不占许可。
- **计费**：LLM 成本按租户归集（token 计量），支撑"分层订阅 + 用量"混合计费。
- **平台超管**：`app_user.role ∈ {SUPER_ADMIN, USER}`，超管 `tenant_id=0` 可跨租户运营；JWT 带 `role` 声明，`/api/admin/**` 需 `ROLE_SUPER_ADMIN`。首个超管由 `DataSeeder` 播种（用户名 `superadmin`、口令取环境变量 `AIBI_SUPERADMIN_PASSWORD`、默认 `[redacted-default-pwd]`）；后台可管理租户 CRUD、账号、大模型配置（全局默认 + 租户覆盖，`api_key` 经 `CredentialCipher` 加密落 `llm_config`、只写不读）、套餐/期限/配额。

---

## 11. 落地映射（代码骨架）

| 设计能力 | 代码位置 |
| --- | --- |
| 租户上下文 | `common/tenant/TenantContext.java` |
| 套餐与隔离模式 | `common/tenant/PlanType.java`、`IsolationMode.java` |
| JWT 租户传播 | `common/security/JwtTokenProvider.java`、`JwtAuthFilter.java` |
| POOL 行级隔离 | `common/config/MybatisPlusConfig.java`（TenantLineInnerInterceptor） |
| 凭证加密 | `module/datasource/security/CredentialCipher.java` |
| 数据库类型 | `module/datasource/DataSourceType.java` |
| 方言 | `module/datasource/dialect/SqlDialect.java`、`SqlDialects.java` |
| 适配器 | `module/datasource/adapter/JdbcDataSourceAdapter.java`、`CsvFileAdapter.java` |
| 适配器注册表 | `module/datasource/adapter/DataSourceAdapterRegistry.java` |
| 租户级连接池 | `module/datasource/TenantDataSourceManager.java` |
| 数据源服务/编目 | `module/datasource/service/DataSourceService.java` |
| 元数据表 | `resources/db/metadata-schema.sql`（tenant / data_source） |
| 问数多租户化 | `module/chat/*`（按 datasourceId 路由） |
| 配额限流（DB 持久化） | `module/quota/*`（QuotaService / QuotaUsage） |
| 大模型配置落库 | `module/chat/llm/*`（LlmConfig / LlmRouter 按租户解析链） |
| 超管后台 | `module/admin/*`（租户 / 账号 / LLM 配置 / 概览，仅 SUPER_ADMIN） |
| 指标与驾驶舱 | `module/dashboard/*`（metric / dashboard / template） |
| 存量库迁移 | `common/config/SchemaMigration.java`（查 information_schema 幂等加列） |

---

## 12. 演进路线

| 阶段 | 隔离能力 | 数据源能力 |
| --- | --- | --- |
| MVP | POOL（tenant_id 自动注入）+ 凭证加密 | MySQL 完整，PG/ClickHouse/CSV 打通 |
| V1.0 | POOL 稳定 + 配额限流 + 审计 | 关系型 + OLAP 全覆盖，Excel 支持 |
| V2.0 | 企业版 DATABASE/SCHEMA + 私有化部署 | Hive/Presto/Trino、更多国产库（达梦/OceanBase） |

> 触发升级信号：单租户数据量/并发逼近共享库瓶颈 → SCHEMA；合规或大客户强隔离要求 → DATABASE/私有化。
