# AI 数据助理 · MVP 后端（SaaS 多租户 + 多数据源 + 对话式问数）

模块化单体（Spring Boot 3.2 + Java 17），适配单台 **4核4G**。对应《前后端架构设计文档》第三章、
《SaaS 多租户与数据源接入设计》与《开发计划文档》Sprint 1–5。

> **已落地能力**：统一响应 + 全局异常 + JWT 安全 + 多租户隔离 + 多类型数据源接入 + NL2SQL 问数闭环
> \+ 用户模块（注册/登录）+ 数据源管理 API + 多 LLM 降级路由 + 图表推荐 + 并发护栏 + 查询历史/多轮对话
> \+ 配额限流 + 分享链接（快照 + 公开访问）+ SSE 流式问数 + 大屏公开分享（实时）+ 文件本地上传（CSV/Excel）+ 动态推荐问题 + 收藏/指标去重。
>
> 缓存 / 配额 / 并发护栏等以**进程内实现**落地（适配单机 4C4G，零外部中间件依赖），并在下文标注
> 生产多实例部署时的 **Redis 平替路径**。

## 目录结构

```
backend/
├── pom.xml                                   # + MyBatis-Plus（租户隔离）/ PostgreSQL / SQLite / commons-csv / POI(Excel)
├── Dockerfile                                # 4核4G 轻量运行镜像（JRE + JVM 内存护栏）
└── src/main/
    ├── java/com/aibi/
    │   ├── AiBiApplication.java              # 启动类（@MapperScan）+ 元数据库 JdbcTemplate（仅供 DataSeeder 播种）
    │   ├── common/
    │   │   ├── response/                     # R（统一响应）/ PageResult（分页壳）
    │   │   ├── exception/                    # ErrorCode / BizException / GlobalExceptionHandler（友好错误）
    │   │   ├── security/                     # JwtTokenProvider / JwtAuthFilter / UserContext / SecurityConfig
    │   │   ├── tenant/                       # TenantContext / PlanType / IsolationMode  ← 多租户核心
    │   │   └── config/                       # MybatisPlusConfig(POOL 行级隔离) / DataSeeder / SchemaMigration(存量库幂等加列) / AsyncConfig(SSE 线程池)
    │   └── module/
    │       ├── auth/                         # ← 用户模块（T1.4，MyBatis-Plus 四层）
    │       │   ├── controller/AuthController # 注册 / 登录 / 登出 / me / 更新资料
    │       │   ├── service/UserService       # 业务接口（extends IService<User>）
    │       │   ├── service/impl/UserServiceImpl  # extends ServiceImpl<UserMapper,User>：自助开通租户、BCrypt、签发 JWT
    │       │   ├── entity / mapper / dto     # User(app_user) / Tenant + BaseMapper + 请求/响应 DTO
    │       ├── datasource/                   # ← 多数据源接入 + 管理 API（T2.1/T2.2/T2.3，MyBatis-Plus 四层）
    │       │   ├── controller/DataSourceController   # CRUD + 连通性测试 + 表结构查看
    │       │   ├── service/DataSourceService         # 业务接口（extends IService<DataSource>）
    │       │   ├── service/impl/DataSourceServiceImpl    # 凭证加解密 + DataSourceConfig 转换 + 连通性测试
    │       │   ├── entity/DataSource(data_source，@TableLogic status) / mapper/DataSourceMapper
    │       │   ├── DataSourceType / TenantDataSourceManager / SchemaCache（进程内 TTL 缓存）
    │       │   ├── dialect/                          # SqlDialect / SqlDialects（方言差异收敛）
    │       │   ├── adapter/                          # 适配器 + 注册表（Jdbc / CsvFile / ExcelFile(POI)）+ 本地上传落盘
    │       │   ├── model/                            # DataSourceConfig / TableMeta / QueryResult
    │       │   ├── dto/                              # DataSourceRequest / DataSourceVO / TestConnectionResult / FileUploadResult
    │       │   └── security/CredentialCipher         # 凭证 AES-GCM 加解密
    │       ├── chat/                         # ← 对话式问数（核心）
    │       │   ├── controller/                       # ChatController(POST /query · GET /suggestions) / ChatStreamController(SSE /query/stream)
    │       │   ├── service/ChatService               # 编排：配额→护栏→NL2SQL→图表→历史落库→多轮
    │       │   ├── service/SuggestionService          # 问题4：依表结构 LLM 生成推荐问题（进程内缓存 + 规则降级）
    │       │   ├── nl2sql/                           # ★ 核心引擎层（§3.6）
    │       │   │   ├── Nl2SqlEngine                  # 编排：意图→裁剪→少样本→Prompt→LLM→校验→执行→修正
    │       │   │   ├── IntentClassifier / Intent     # 规则意图分类（趋势/对比/排名/明细/聚合）
    │       │   │   ├── SchemaProvider / SchemaLinker / SchemaSlice  # 表结构扫描 + 相关表裁剪
    │       │   │   ├── PromptBuilder / FewShot(Store)# Prompt 组装（含多轮上下文）+ 历史成功问答少样本
    │       │   │   ├── SqlValidator / SqlRepairer    # 只读安全校验 + 执行报错回喂 LLM 修正（重试≤2）
    │       │   │   └── QueryContext / ProgressListener / ConversationTurn  # 上下文载体 + 分步进度回调 + 多轮
    │       │   ├── llm/                              # ← 多 LLM 抽象（M8）
    │       │   │   ├── LlmClient / AbstractOpenAiCompatibleClient  # 接口 + OpenAI 兼容协议基类
    │       │   │   ├── DeepSeekClient / QwenClient   # 主力 + 降级备选
    │       │   │   └── LlmRouter                     # 按可用性+优先级降级路由（deepseek→qwen）
    │       │   ├── chart/                            # ← 图表推荐（M3）：ChartRecommender / ChartSpec / ChartType
    │       │   ├── guard/ConcurrencyGuard            # ← 并发护栏（M9）：公平信号量 + 超时快速失败
    │       │   ├── sse/SseProgressListener           # ← SSE 进度监听（M5）：把引擎分步进度推成事件
    │       │   └── dto/                              # ChatQueryRequest / ChatQueryResponse（含 chart/会话/历史 id）
    │       ├── history/                      # ← 查询历史 + 多轮（M4，MyBatis-Plus 四层）：Controller / Service+Impl / QueryHistory / Mapper / VO
    │       ├── favorite/                     # ← 问数收藏（M4，MyBatis-Plus 四层）：Controller / Service+Impl / QueryFavorite / Mapper / VO
    │       ├── quota/                        # ← 配额限流（M6）：Controller / QuotaService(DB 持久化日限/总限/期限) / QuotaUsage / QuotaStatus
    │       ├── share/                        # ← 分享链接（M7，MyBatis-Plus 四层）：Controller / PublicShareController / Service+Impl / ShareLink / Mapper / DTO
    │       ├── admin/                        # ← 超管后台（Feature 1）：租户/账号/LLM 配置/概览 CRUD（仅 SUPER_ADMIN）
    │       └── dashboard/                    # ← 指标与驾驶舱（Feature 2）：metric/dashboard/template + 刷新/自动布局/模板实例化 + 大屏公开分享（问题2）
    └── resources/
        ├── application.yml                   # 元数据库 + JWT + LLM(主/备) + 护栏 + 配额 + 分享 + 凭证密钥 + 演示数据源
        └── db/metadata-schema.sql            # 元数据表 DDL（tenant / data_source / query_history / app_user / query_favorite / share_link）
```

## 根目录脚本索引（T6 收尾）

> ★收敛方式：**不搬文件、只建索引**。根目录脚本已被 `docs/` 下的变更记录以 20+ 处按路径引用（尤其是发布工具），物理搬家会打断文档与既有配方；改为下表声明各自身份与去留，**新的一次性脚本一律写 `backend/scripts/`**，根目录只放长期工具。

| 分类 | 文件 | 用途 | 去留 |
|------|------|------|------|
| 发布工具（长期） | `deploy_update.py` / `deploy_dist_only.py` / `deploy_restore.py` | jar+dist 全量发布 / 仅发前端 / 回滚；口令只经位置参数或 `PROD_PW` 传入 | 保留，禁删 |
| 发布工具（长期） | `publish_manual.py` | 使用手册发布（凭据走 `scripts/envutil`） | 保留 |
| 本地运行（长期） | `run-serve.ps1` / `local.env.example` / `Dockerfile` / `pom.xml` | 启动与配置模板（`local.env` 已 gitignore） | 保留 |
| 测试与冒烟 | `tests/`（T4 E2E 套件）/ `run_tests.ps1` | 回归用例与汇总 runner | 保留（入库主体） |
| 测试与冒烟 | `api-smoke.ps1` / `feature-smoke.ps1` / `b_verify_403.ps1` | 历史逐轮冒烟（部分口令为本地演示账号） | 保留，待被 `tests/` 完全覆盖后可归档 |
| 一次性（备查） | `t4_deploy.py` / `t4_redeploy_dist.py` / `t4_apply_dev_sql.py` | T4 当时的发布/补建 dev 库表脚本 | 不再新增同类；需要时归档至 `_archive/` |
| 本地评测 | `bench/` | NL2SQL 评测工具集（**含 `llm_keys.env` 真实 LLM Key，整目录已 gitignore**） | 不入库 |
| SQL | `sql/` | 手工增量脚本（不在 `spring.sql.init` 链路内，见清单 T8 邻域待办） | 保留 |

## 前置条件

| 项 | 要求 |
|----|------|
| JDK | 17+（本机 22 可编译运行，pom 以 java.version=17） |
| Maven | 3.6.3+（本机 3.6.3 已验证）；**编译前必须让 JAVA_HOME 指向 JDK 17+**，否则默认 JDK 8 会失败 |
| MySQL | 本地 8.x，root/root（或自行改配置）；元数据库 `aibi_meta` 会**自动创建** |
| 业务库 | 已导入 `../poc/nl2sql-poc/sql/sample_schema.sql`（示例业务库 `aibi_demo`） |
| LLM | DeepSeek API Key（可选再配通义千问 Key 获得跨供应商容灾） |

## 运行

**1. 准备演示业务库**（用户自有数据的替身）

```bash
mysql -uroot -p < ../poc/nl2sql-poc/sql/sample_schema.sql
```

**2. 注入环境变量**（PowerShell）

```powershell
$env:DEEPSEEK_API_KEY = "sk-xxxxxxxx"
$env:QWEN_API_KEY     = "sk-xxxxxxxx"   # 可选：配置后 LlmRouter 在 DeepSeek 失败时自动降级
$env:JWT_SECRET       = "please-change-this-to-a-32+bytes-random-secret-key"
$env:AIBI_CIPHER_KEY  = "please-change-this-cipher-key"   # 数据源凭证加密主密钥
```

**3. 启动**

```bash
mvn spring-boot:run
# 或 IDE 运行 AiBiApplication.main()
```

启动时自动完成：`createDatabaseIfNotExist` 建 `aibi_meta` → `spring.sql.init` 建元数据表 →
`SchemaMigration` 对**存量库**幂等补列（`tenant` 配额/期限、`app_user.role` 等）→ `DataSeeder` 播种演示租户（id=1, PRO）、
演示数据源（id=1 → aibi_demo）、**首个超管**（`superadmin`，口令取 `AIBI_SUPERADMIN_PASSWORD`、默认 `[redacted-default-pwd]`）、
全局默认 LLM 配置与 4 套内置驾驶舱模板。服务监听 `http://localhost:8080`。

## REST API 一览

除标注「免认证」外，全部需请求头 `Authorization: Bearer <token>`；所有响应统一为 `R{code,message,data,traceId}`。

| 模块 | 方法 & 路径 | 说明 |
|------|-------------|------|
| 认证 | `POST /api/auth/register` | 注册（自助开通 FREE/POOL 租户）**免认证** |
| 认证 | `POST /api/auth/login` | 登录，签发含 uid/tid/plan 的 JWT **免认证** |
| 认证 | `POST /api/auth/logout` | 登出（无状态，前端丢弃令牌即可） |
| 认证 | `GET /api/auth/me` · `PUT /api/auth/me` | 当前用户 · 更新资料 |
| 数据源 | `GET /api/datasource` | 我的数据源列表 |
| 数据源 | `POST /api/datasource` · `PUT /api/datasource/{id}` · `DELETE /api/datasource/{id}` | 新建 · 更新 · 删除 |
| 数据源 | `POST /api/datasource/test` · `POST /api/datasource/{id}/test` | 测试未保存配置 · 测试已存数据源 |
| 数据源 | `GET /api/datasource/{id}/schema` | 查看表结构（前端展示 / 常见问题推荐） |
| 数据源 | `POST /api/datasource/upload` | **本地上传 CSV/Excel**（问题3）：落盘 + 解析列/预览行，返回 `filePath` 供回填后入库（multipart `file`） |
| 问数 | `POST /api/chat/query` | 一次性问数，返回结果 + 图表推荐 + 会话/历史 id |
| 问数 | `GET /api/chat/query/stream` | **SSE 流式问数**，分步推送 `stage` → `result` / `error` |
| 问数 | `GET /api/chat/suggestions?datasourceId=` | **动态推荐问题**（问题4）：依表结构 LLM 生成，带进程内缓存与规则降级 |
| 历史 | `GET /api/history` | 分页「我的历史」（可按 `datasourceId` 过滤，`limit`/`offset`） |
| 历史 | `GET /api/history/conversations` | 会话列表（多轮对话侧边栏） |
| 历史 | `GET /api/history/{id}` · `DELETE /api/history/{id}` | 单条详情 · 删除 |
| 收藏 | `GET /api/favorite` · `POST /api/favorite` · `DELETE /api/favorite/{id}` | 列表 · 新增（问题1：**幂等去重**，同用户+数据源+问题已存在则返回既有项）· 删除 |
| 配额 | `GET /api/quota` | 今日配额状态（plan/limit/used/remaining） |
| 分享 | `POST /api/share` · `GET /api/share` · `DELETE /api/share/{id}` | 创建分享 · 我的分享 · 删除 |
| 分享 | `GET /api/public/share/{token}?password=` | **公开只读访问快照** **免认证** |
| 超管 | `GET/POST /api/admin/tenants` · `GET/PUT/DELETE /api/admin/tenants/{id}` · `POST /api/admin/tenants/{id}/status` | 租户 CRUD/启停（含套餐/期限/日限/总限）**仅 SUPER_ADMIN** |
| 超管 | `GET/POST /api/admin/users` · `GET/PUT/DELETE /api/admin/users/{id}` · `POST /api/admin/users/{id}/status` · `POST /api/admin/users/{id}/reset-password` | 平台账号 CRUD/启停/重置密码 **仅 SUPER_ADMIN** |
| 超管 | `GET/POST /api/admin/llm-configs` · `GET/PUT/DELETE /api/admin/llm-configs/{id}` · `POST /api/admin/llm-configs/{id}/status` · `POST /api/admin/llm-configs/test` · `POST /api/admin/llm-configs/{id}/test` | 全局+租户 LLM 配置 CRUD/启停/连通测试 **仅 SUPER_ADMIN** |
| 超管 | `GET /api/admin/overview` | 平台概览统计（租户/账号/问数/失败率）**仅 SUPER_ADMIN** |
| 指标 | `GET/POST /api/metric` · `GET/PUT/DELETE /api/metric/{id}` · `POST /api/metric/{id}/refresh` | 指标 CRUD + **实时重跑已存 SQL**（不耗 LLM/不计配额） |
| 驾驶舱 | `GET/POST /api/dashboard` · `GET/PUT/DELETE /api/dashboard/{id}` · `GET /api/dashboard/{id}/data` | 驾驶舱 CRUD + **大屏批量刷新**（布局+各 widget 实时数据） |
| 驾驶舱 | `POST /api/dashboard/auto-design` · `POST /api/dashboard/from-template` | 一键自动布局 / 从内置模板实例化 |
| 驾驶舱 | `GET /api/dashboard/{id}/share` · `PUT /api/dashboard/{id}/share` | 问题2：查询 / 开启·更新·关闭**大屏公开分享**（密码/有效期），返回可访问 url |
| 驾驶舱 | `GET /api/public/dashboard/{token}?password=` | **公开实时访问大屏**（问题2）**免认证**：按 token 在其租户上下文实时重跑各 widget |
| 模板 | `GET /api/dashboard-template` | 内置驾驶舱模板列表（只读，含骨架布局） |

## 接口联调

**① 注册 / 登录获取 token**（演示账号 admin / [see-startup-log]，签发含 `tid=1`、`plan=PRO` 的 JWT；超管 `superadmin` / `[redacted-default-pwd]` 签发 `role=SUPER_ADMIN`、`tid=0`，可访问 `/api/admin/**`）

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"[set-via-env]"}'
```

**② 携带 token 问数**（请求体带 `datasourceId`；`conversationId` 可选，追问时回传以启用多轮）

```bash
curl -X POST http://localhost:8080/api/chat/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <上一步的 token>" \
  -d '{"datasourceId":1,"question":"每个地区的已支付订单总金额是多少"}'
```

返回（`data` 为 `ChatQueryResponse`：结果 + 图表推荐 + 会话/历史 id）：

```json
{ "code": 0, "message": "success",
  "data": {
    "conversationId": "3f2a9c1e6b7d4a2f8c0e5b1a9d7c3f2e",
    "historyId": 42,
    "result": {
      "sql": "SELECT c.region, SUM(o.amount) ... GROUP BY c.region LIMIT 10000",
      "columns": ["region", "total_amount"],
      "rows": [["华东", 1899.00], ["华南", 99.00]],
      "rowCount": 2,
      "executionTimeMs": 23
    },
    "chart": {
      "type": "BAR", "title": null,
      "xField": "region", "yFields": ["total_amount"], "seriesField": null,
      "reason": "分类维度 region + 度量 total_amount，适合柱状图对比"
    }
  } }
```

**③ SSE 流式问数**（浏览器 `EventSource` 或 curl；先收若干 `stage` 进度，最后收 `result`）

```bash
curl -N "http://localhost:8080/api/chat/query/stream?datasourceId=1&question=每月订单趋势" \
  -H "Authorization: Bearer <token>"
```

```
event: stage
data: {"stage":"intent","message":"识别意图：趋势"}

event: stage
data: {"stage":"chart","message":"已推荐图表：折线图"}

event: result
data: {"conversationId":"...","historyId":43,"result":{...},"chart":{"type":"LINE",...}}
```

> stage 载荷字段为 `{stage, message}`（见 `SseProgressListener#onStage`）。前端因 `EventSource` 无法携带 `Authorization` 头，改用 `fetch` + `ReadableStream` 手动解析 `text/event-stream`。

**④ 分享与公开访问**（快照只读，不回查业务库）

```bash
# 创建分享（提交已得到的 result/chart 作为快照）
curl -X POST http://localhost:8080/api/share -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"datasourceId":1,"question":"各地区金额","result":{...},"chart":{...},"expireHours":72,"password":"1234"}'
# → data.url = /share/{token}（或配置 public-base-url 后为绝对地址）

# 公开访问（免认证；有密码则带 password）
curl "http://localhost:8080/api/public/share/{token}?password=1234"
# → data.status ∈ {OK, PASSWORD_REQUIRED, PASSWORD_WRONG, EXPIRED, NOT_FOUND}
```

**⑤ 鉴权与隔离**：未带 token → HTTP 401；访问其他租户的 `datasourceId` → `40401 数据源不存在或无权访问`。

**⑥ 一键联调冒烟脚本**（需后端已启动、MySQL 可达；仅用开发默认口令，不含真实密钥）

| 脚本 | 覆盖 | 说明 |
| --- | --- | --- |
| [`api-smoke.ps1`](api-smoke.ps1) | Feature 1/2 | 超管登录鉴权、租户/账号 CRUD、配额门禁、LLM 配置、模板/指标/驾驶舱、数据源/历史回归 |
| [`feature-smoke.ps1`](feature-smoke.ps1) | 问题 1-6 | 收藏/指标去重、大屏公开分享（令牌/密码门/关闭失效）、文件本地上传解析、依 schema 推荐问题、六套大屏主题持久化 |

```bash
powershell -ExecutionPolicy Bypass -File backend\feature-smoke.ps1 [-BaseUrl http://localhost:8080] [-XlsxPath <path.xlsx>]
```

> 两脚本均直接打到 `:8080`，以 `PASS/FAIL` 计数并在末尾给出汇总（有 FAIL 则 `exit 1`）。`feature-smoke.ps1` 会自动生成临时 CSV 夹具、跑完即清理（删除本脚本创建的数据源/指标/收藏/大屏）。**注**：含中文的 `.ps1` 须存为 UTF-8 带 BOM，否则 Windows PowerShell 5.1 按 GBK 解码会报解析错误。

## 核心能力

### NL2SQL 问数闭环（§3.6）
`Nl2SqlEngine` 编排七步：**意图分类 → 相关表裁剪（SchemaLinker，省 token）→ 少样本检索（历史成功问答）
→ Prompt 组装（含多轮上下文）→ LLM 生成 SQL → 只读安全校验（SqlValidator）→ 执行**；执行报错时
`SqlRepairer` 回喂错误让 LLM 修正（重试 ≤2）。表结构经 `SchemaCache` 进程内 TTL 缓存。

### 多 LLM 抽象与降级路由（M8）
`LlmClient` 接口 → `AbstractOpenAiCompatibleClient`（OpenAI 兼容协议基类，仅用 JDK `HttpClient` + Jackson，
不引 SDK）→ `DeepSeekClient` / `QwenClient`。`LlmRouter` 按可用性 + 优先级降级：只尝试 `isAvailable()`（配了 Key）
的客户端，主力失败自动切备选，全败抛最后一次异常。**仅配 DeepSeek 即可运行**；两者都配即获跨供应商容灾。
配置现已**落库 `llm_config`**（全局行 `tenant_id=NULL` + 租户行覆盖，`api_key` 经 `CredentialCipher` 加密、只写不读），
`LlmRouter` 按当前租户动态解析链（租户专属优先 → 全局兜底 → `application.yml` env 播种默认）；超管可在后台增改/启停/设优先级/测连通。

### 图表推荐（M3）
零依赖规则引擎 `ChartRecommender`：对结果列做**列角色判定**（时间 / 类别 / 度量，取样值运行时类型 + 列名关键词
双重启发式），再按优先级选图 —— 无度量或多行明细 → 明细表；含时间维 → 折线；占比 + 单度量 + ≤8 类 → 饼；
分类 + 度量 → 柱状（两个类别维时第二维作 series 分组）；兜底 → 明细表。产物 `ChartSpec` 附**推荐理由**增强可解释性。

### 多轮对话与查询历史（M4）
问数以 `conversationId` 串起会话：首轮生成、追问复用；`QueryHistoryService.recentTurns` 取该会话最近 N 轮成功问答
注入 Prompt，理解「那再看看华南」「按月份拆开」等指代与省略。历史自动落库（成功/失败均记），支撑审计、计费、少样本。

### 并发护栏与配额限流（M9 / M6）
- **并发护栏**：`ConcurrencyGuard` 以公平 `Semaphore(max-concurrent=5)` 限制同时执行的问数（LLM+DB），
  `tryAcquire(acquire-timeout-ms=3000)` 超时即快速失败 `42902`，保护 4C4G 单机不被压垮。
- **配额限流**：`QuotaService` 读取 `tenant.daily_limit/total_limit/expire_at` 做门禁（过期 `40304`/停用 `40305` 即拒），
  日计数落 `quota_usage`（`INSERT ... ON DUPLICATE KEY UPDATE used=used+1`）、累计走 `tenant.total_used`，超限回滚并抛
  `42901/42903`；建租户时按套餐给默认日限（FREE=20/PRO=1000，`-1`=不限），超管可在后台改。
  配额校验在并发护栏**之前**，超限不误落历史、不占许可。

### 分享链接（M7）
分享的是**结果快照**：把已得到的 `result`（截断至 `snapshot-max-rows=500` 行）与 `chart` 序列化落库（MEDIUMTEXT），
公开访问只读快照、绝不实时回查业务库。`token` 为 UUID 能力令牌按 token 直查；密码 BCrypt 哈希；支持有效期
（`expireHours`）；免费版强制水印。公开访问统一返回 `status`（OK / PASSWORD_REQUIRED / PASSWORD_WRONG / EXPIRED / NOT_FOUND）。

### SSE 流式问数（M5）
`GET /api/chat/query/stream` 返回 `SseEmitter`，由 `AsyncConfig` 的 `sseExecutor` 线程池后台执行；引擎的
`ProgressListener` 分步进度实时推成 `event: stage`，最终 `event: result`，出错 `event: error`。因 JWT 过滤器在请求
返回后清理 ThreadLocal，控制器在**请求线程内快照** `TenantContext`/`UserContext` 后在工作线程重建、结束清理，杜绝串租户。

### 大屏公开分享（问题2）
与结果分享的**快照**不同，大屏分享是**实时**的：`dashboard` 增 `share_token/share_password/share_expire_at`，`PUT /api/dashboard/{id}/share`
开启/更新/关闭（关闭即清令牌、旧链接立即失效）；`GET /api/public/dashboard/{token}` 免认证访问——按 token 直查（`@InterceptorIgnore`
关租户注入）后**临时设 `TenantContext`** 令租户拦截器与连接池按大屏所属租户解析，实时重跑各 widget 指标 SQL，取数毕立即 `clear()`
杜绝串租户。密码 BCrypt、支持有效期，状态复用 `OK/PASSWORD_REQUIRED/PASSWORD_WRONG/EXPIRED/NOT_FOUND`；前端公开路由 `/share/screen/{token}`。

### 文件数据源本地上传（问题3）
`POST /api/datasource/upload`（multipart）落盘到 `aibi.upload.dir`（**按租户分子目录 + uuid 重命名**，杜绝路径穿越），CSV 走 commons-csv、
Excel 走 **POI**，统一载入内存 SQLite 供只读查询；返回 `filePath` + 列名 + 预览行（`preview-rows`，超限 `truncated=true`），前端预览确认后回填入库。
扩展名/大小超限分别抛 `40003/40004`，落盘失败 `50005`。

### 动态推荐问题（问题4）
`GET /api/chat/suggestions?datasourceId=` 由 `SuggestionService` 依表结构（表/列/注释）用 LLM 生成贴合数据的问题；**进程内缓存**（按数据源）
避免重复调用，LLM 不可用时**降级**为基于列角色的规则模板，保证前端始终有推荐可展示。

### 收藏 / 指标去重（问题1）
`POST /api/favorite` **幂等**：同 `uid+datasourceId+question` 已存在则直接返回既有项 id（不报错）；`POST /api/metric` 对重复问数**拒绝**并抛
`PARAM_ERROR`（「该问数已保存为指标，请勿重复保存」），前端拦截器透出提示。

## 多租户与数据隔离

- **租户身份**：登录签发 JWT 携带 `tid`/`plan` → `JwtAuthFilter` 解析写入 `TenantContext`（ThreadLocal），请求结束即清理，杜绝串租户。
- **POOL 行级隔离**：`history`/`favorite`/`share`/`datasource` 均为 MyBatis-Plus 四层，`MybatisPlusConfig` 的 `TenantLineInnerInterceptor` 对这四张业务表**自动注入** `tenant_id`，无需每条 SQL 手写；`app_user`/`tenant` 在 IGNORE 名单（登录前无租户上下文，按 username/uid 全局寻址）。对匿名公开访问（分享按 token 直查）与复杂 JOIN 聚合的个别 Mapper 方法，用 `@InterceptorIgnore(tenantLine="true")` 关闭注入并显式传 `tenant_id`。用户级 `uid` 过滤仍由 Service 参数显式携带。
- **连接隔离**：`TenantDataSourceManager` 按 `tenantId:datasourceId` 维护独立只读连接池，绝不跨租户复用。
- **凭证加密**：数据源密码经 `CredentialCipher`（AES-GCM）加密落库，密钥由 `AIBI_CIPHER_KEY` 注入，与库分离。
- **分层隔离**：`PlanType` 决定 `IsolationMode`——免费/专业版 POOL，企业版 DATABASE/SCHEMA（见设计文档 3）。

## 多数据源接入

| 类别 | 类型 | 状态 |
|------|------|------|
| 关系型 | MySQL / MariaDB / PostgreSQL / SQLite | ✅ 驱动已内置 |
| 关系型 | SQL Server / Oracle | 🔌 放开 pom 注释即可 |
| OLAP | ClickHouse | 🔌 放开 pom 注释即可 |
| OLAP | Doris / StarRocks | ✅ 复用 MySQL 驱动 |
| OLAP | Hive / Presto / Trino | 🔌 放开 pom 注释即可 |
| 文件 | CSV | ✅ 本地上传 → 载入内存 SQLite 查询 |
| 文件 | Excel | ✅ 本地上传（POI 解析 .xlsx/.xls）→ 载入内存 SQLite 查询 |

新增一种 JDBC 数据库：在 `DataSourceType` 登记驱动/URL/端口 + 在 pom 放开驱动依赖即可，业务代码零改动。
数据库语法差异（标识符引用、分页上限）由 `SqlDialect` 承担，通用适配器无感知。

## 安全护栏（已内置）

- 仅放行 `/api/auth/login`、`/api/auth/register`、`/api/public/**`、`/actuator/health`、`/error`，其余需有效 JWT。
- `SqlValidator`：只允许单条 SELECT，拦截写操作 / 多语句 / `INTO OUTFILE`。
- 三重只读限流：连接池 `readOnly` + 方言 `applyLimit` 软限制 + JDBC `setMaxRows`/`setQueryTimeout` 硬限制。
- 全局并发护栏（≤5）+ 按租户每日配额，防单机过载与滥用。
- `GlobalExceptionHandler` 统一兜底：业务/参数/请求体/缺参/类型不匹配/校验等转友好中文提示，不泄露堆栈。
- 跨租户访问一律视为「不存在」（`40401`），不泄露其他租户资源是否存在。

## 配置项参考（`application.yml` → `aibi.*`）

| 配置键 | 默认 | 说明 |
|--------|------|------|
| `jwt.secret` / `jwt.expire-minutes` | 环境变量 / 120 | JWT 密钥（≥32 字节）与有效期 |
| `llm.base-url` / `api-key` / `model` / `timeout-seconds` | DeepSeek | 主力 LLM（OpenAI 兼容） |
| `llm.fallback.*` | 通义千问 | 降级备选；未配 `api-key` 时 `LlmRouter` 自动跳过 |
| `query.timeout-seconds` / `max-rows` | 30 / 10000 | 单次查询超时与最大返回行数 |
| `query.max-concurrent` / `acquire-timeout-ms` | 5 / 3000 | 并发护栏上限与获取许可超时（M9） |
| `nl2sql.few-shot-limit` / `max-repair` / `conversation-turns` | 3 / 2 / 3 | 少样本条数 / 修正重试 / 多轮注入轮数 |
| `schema.cache-ttl-seconds` | 1800 | 表结构缓存 TTL（0=不缓存；生产平替 Redis） |
| `quota.free-daily` / `pro-daily` | 20 / 1000 | 各套餐每日问数上限（`-1`=不限；企业版恒定不限）（M6） |
| `share.snapshot-max-rows` / `public-base-url` | 500 / 空 | 快照最大行数 / 公开访问页基础地址（M7） |
| `upload.dir` / `max-size-mb` / `preview-rows` / `allowed-extensions` | `./data/uploads` / 20 / 20 / csv,xlsx,xls | 问题3：本地上传根目录（按租户分子目录 + uuid 重命名）/ 单文件上限 / 预览行数 / 允许扩展名 |
| `datasource.cipher-key` / `pool.max-size` | 环境变量 / 3 | 凭证 AES-GCM 主密钥 / 每数据源连接池上限 |
| `demo.*` | — | `DataSeeder` 播种的演示租户与数据源 |

## 元数据库表（`db/metadata-schema.sql`，启动自动建表）

| 表 | 用途 | 隔离 |
|----|------|------|
| `tenant` | 租户（套餐 / 隔离模式） | 全局表 |
| `app_user` | 用户（BCrypt 密码） | `tenant_id`（登录前无租户上下文，列入 MP 拦截器 IGNORE） |
| `data_source` | 数据源连接配置（凭证密文） | `tenant_id` |
| `query_history` | 问数历史（含 `conversation_id`，供审计/计费/多轮/少样本） | `tenant_id` + `uid` |
| `query_favorite` | 问数收藏 | `tenant_id` + `uid` |
| `share_link` | 分享（token 唯一 + 结果快照 + 图表 + 密码/有效期/水印/访问数） | `tenant_id`（公开访问按 token） |
| `llm_config` | 大模型配置（全局默认 `tenant_id=NULL` + 租户覆盖；`api_key` 密文；priority 降级） | 全局表（IGNORE，手写 `tenant_id=? OR IS NULL`） |
| `quota_usage` | 按日配额计数（`tenant_id+usage_date` 唯一，upsert 累加） | `tenant_id` |
| `metric` | 保存的问数指标（name/sql/chartSpec，供驾驶舱复用） | `tenant_id` |
| `dashboard` | 驾驶舱（`layout` 12 列网格 JSON / theme / refreshSeconds / isScreen / `share_token`·`share_password`·`share_expire_at` 公开分享） | `tenant_id`（公开访问按 token） |
| `dashboard_template` | 内置驾驶舱模板（骨架布局含占位槽） | 全局表（IGNORE） |

> **存量库升级**：`tenant` 增 `expire_at/daily_limit/total_limit/total_used`、`app_user` 增 `role`。MySQL 不支持
> `ADD COLUMN IF NOT EXISTS`，故由 `SchemaMigration`（`ApplicationRunner`）查 `information_schema.columns` 后幂等补列，
> 已存在的 `aibi_meta` 可平滑升级；新库则由 `metadata-schema.sql` 直接建全字段。

## 打包与容器化

```bash
mvn clean package -DskipTests      # 产出 target/aibi.jar
docker build -t aibi-backend:0.1.0 .
docker run -p 8080:8080 \
  -e DEEPSEEK_API_KEY=sk-xxx -e JWT_SECRET=xxx -e AIBI_CIPHER_KEY=xxx \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host:3306/aibi_meta?createDatabaseIfNotExist=true" \
  aibi-backend:0.1.0
```

## 完成情况（对照开发计划）

| Sprint | 任务 | 状态 |
|--------|------|------|
| S1 T1.4 | 用户模块：用户表 + 注册 + 登录（MyBatis-Plus Mapper） | ✅ 已完成 |
| S2 | 数据源管理 API：CRUD + 连通性测试 + schema 缓存 | ✅ 已完成（缓存进程内 TTL，生产平替 Redis） |
| S3 | NL2SQL 增强：引擎 + 意图分类 + SchemaLinker 裁剪 + 少样本 + 执行反馈修正 | ✅ 已完成（待增强：embedding 相似度、LLM 意图兜底） |
| S4 | 问数交互：SSE 流式 + 图表推荐 + 多轮对话 + 历史/收藏 | ✅ 已完成 |
| — | 多 LLM 抽象 + 降级路由 / 并发护栏 / 配额限流 / 分享链接 / 友好错误 | ✅ 已完成 |
| — | 增量修复（问题1–6）：收藏/指标去重 · 大屏公开分享(实时) · 文件本地上传(CSV/Excel) · 动态推荐问题 · 大屏主题(前端) | ✅ 已完成（后端编译/打包级验证通过） |
| V2.0 | 企业版隔离：SCHEMA/DATABASE 动态路由 + 私有化部署 | 🔌 规划中 |

> **说明**：本机无 MySQL / Redis / LLM Key 运行态环境，代码以 **Maven 编译级 + 打包级验证**为准
> （`mvn clean package -DskipTests` 通过，产出可运行 jar）；运行级联调需按「前置条件」备齐依赖后进行。
>
> **前端**：对话式问数前端（Vue3 + TS + Vite + Element Plus + ECharts，克莱因蓝主题）已完成并通过
> `vue-tsc` 类型校验 + `vite build` 生产构建，开发期经 Vite 代理转发 `/api` 至本服务（默认 `:8080`）。
> 详见 [`../frontend/README.md`](../frontend/README.md)。
