-- =============================================================
-- AI 数据助理 · 平台元数据库 DDL（POOL 隔离模式）
-- 库名 aibi_meta，由 application.yml 的 createDatabaseIfNotExist 自动创建，
-- 并由 spring.sql.init 在启动时执行本脚本（幂等：全部 IF NOT EXISTS）。
--
-- 隔离要点：
--   1) 除 tenant 自身外，所有业务元数据表都带 tenant_id 列并建索引；
--   2) MyBatis-Plus TenantLineInnerInterceptor 会对这些表自动追加 tenant_id 条件；
--   3) data_source.password_cipher 存放 AES-GCM 密文，绝不明文。
-- =============================================================

-- 租户表（全局表，不参与 tenant_id 自动注入）
CREATE TABLE IF NOT EXISTS tenant
(
    id             BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    plan           VARCHAR(32)  NOT NULL DEFAULT 'FREE' COMMENT '套餐: FREE/PRO/ENTERPRISE',
    isolation_mode VARCHAR(32)  NOT NULL DEFAULT 'POOL' COMMENT '隔离模式: POOL/SCHEMA/DATABASE',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用',
    expire_at      DATETIME              DEFAULT NULL COMMENT '使用期限（空=不过期）',
    daily_limit    INT          NOT NULL DEFAULT 20 COMMENT '每日生成次数上限',
    total_limit    BIGINT       NOT NULL DEFAULT -1 COMMENT '累计总次数上限（-1=不限）',
    total_used     BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已用次数',
    datasource_limit INT        NOT NULL DEFAULT -1 COMMENT '数据源数量上限（-1=不限）',
    register_ip    VARCHAR(64)           DEFAULT NULL COMMENT '自助注册来源IP（每IP仅可开通一个免费工作区）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='租户';

-- 数据源连接配置（租户隔离表）
CREATE TABLE IF NOT EXISTS data_source
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL COMMENT '所属租户，隔离键',
    name            VARCHAR(128) NOT NULL,
    type            VARCHAR(32)  NOT NULL COMMENT 'MYSQL/POSTGRESQL/CLICKHOUSE/CSV...',
    host            VARCHAR(255)          DEFAULT NULL,
    port            INT                   DEFAULT NULL,
    db_name         VARCHAR(128)          DEFAULT NULL,
    username        VARCHAR(128)          DEFAULT NULL,
    password_cipher VARCHAR(512)          DEFAULT NULL COMMENT 'AES-GCM 密文',
    file_path       VARCHAR(512)          DEFAULT NULL COMMENT '文件类数据源路径',
    jdbc_params     VARCHAR(512)          DEFAULT NULL COMMENT 'k=v&k=v 形式的额外 JDBC 参数',
    sheet_names     VARCHAR(1024)         DEFAULT NULL COMMENT '文件类勾选的工作表名 JSON 数组（空=全部）',
    sheet_merge     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=列结构完全一致的工作表合并为一张表',
    extra_catalogs  VARCHAR(1024)         DEFAULT NULL COMMENT '额外库/模式名 JSON 数组（空=只扫主库），其表名带库前缀以支持跨库 join',
    status          TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_schema_sync_at DATETIME  DEFAULT NULL COMMENT '最近一次表结构同步时间（手动/定时同步刷新缓存后写入）',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据源配置';

-- 查询历史（租户隔离表，用于审计、计费、多轮上下文与少样本）
CREATE TABLE IF NOT EXISTS query_history
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    uid             BIGINT       NOT NULL,
    datasource_id   BIGINT       NOT NULL,
    conversation_id VARCHAR(64)           DEFAULT NULL COMMENT '多轮对话 id（同一会话追问共用）',
    question        VARCHAR(500) NOT NULL,
    sql_text        TEXT,
    row_count       INT                   DEFAULT 0,
    elapsed_ms      BIGINT                DEFAULT 0,
    status          VARCHAR(16)           DEFAULT 'OK' COMMENT 'OK/FAILED',
    -- 以下三列为 SQL 编辑器（P1/P3）埋点：skill_code 用于统计各答问技能命中率，
    -- chart_type 用于统计各图型使用率，template_id 用于区分"临时 SQL"与"跑模板"。
    skill_code      VARCHAR(32)           DEFAULT NULL COMMENT '命中的答问技能编码（direct_sql/nl2sql/…）',
    chart_type      VARCHAR(24)           DEFAULT NULL COMMENT '本次结果采用的图型（LINE/BAR/…）',
    template_id     BIGINT                DEFAULT NULL COMMENT '来源查询模板 id（为空=临时 SQL）',
    spec            TEXT                  DEFAULT NULL COMMENT '构造器规格 JSON（来自可视化构造器运行时才有）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, created_at),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问数历史';

-- 用户表（属于某租户；登录发生在租户上下文建立之前，故列入 MyBatis-Plus 租户拦截器 IGNORE 名单）
-- 表名用 app_user 规避 MySQL 保留字 user，免去反引号在 MyBatis-Plus/JSqlParser 下的兼容问题
CREATE TABLE IF NOT EXISTS app_user
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名，全局唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不明文',
    nickname      VARCHAR(64)           DEFAULT NULL,
    email         VARCHAR(128)          DEFAULT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色: SUPER_ADMIN/USER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    -- 账号级界面主题偏好（系统主题切换，SchemaMigration V2026_09_ui_theme 幂等补齐）
    ui_theme      VARCHAR(16)  NOT NULL DEFAULT 'system' COMMENT 'light/dark/system（system=跟随操作系统）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_app_user_email (email),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户';

-- 问数收藏（租户隔离表）
CREATE TABLE IF NOT EXISTS query_favorite
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    uid           BIGINT       NOT NULL,
    datasource_id BIGINT       NOT NULL,
    title         VARCHAR(128)          DEFAULT NULL,
    question      VARCHAR(500) NOT NULL,
    sql_text      TEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_uid (tenant_id, uid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问数收藏';

-- 分享链接（租户隔离表）：token 公开访问，可带密码/有效期/水印，结果为快照不实时查库
CREATE TABLE IF NOT EXISTS share_link
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    uid             BIGINT       NOT NULL,
    token           VARCHAR(64)  NOT NULL COMMENT '公开访问令牌',
    datasource_id   BIGINT       NOT NULL,
    question        VARCHAR(500) NOT NULL,
    sql_text        TEXT,
    result_snapshot MEDIUMTEXT COMMENT '结果快照 JSON（前 N 行）',
    chart_spec      VARCHAR(512)          DEFAULT NULL COMMENT '图表推荐 JSON',
    password        VARCHAR(64)           DEFAULT NULL COMMENT '访问密码（可空）',
    expire_at       DATETIME              DEFAULT NULL COMMENT '过期时间（空=不过期）',
    watermark       TINYINT      NOT NULL DEFAULT 0 COMMENT '免费版加水印',
    view_count      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token (token),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='分享链接';

-- 对话问数嵌入（第三方页面匿名嵌入令牌）：与 share_link 的"结果快照"不同，这里凭 token 实时问数。
-- 令牌即唯一能力凭证，只开放"对指定数据源提问"这一件事；allowed_origins 空=不限制嵌入站点。
CREATE TABLE IF NOT EXISTS chat_embed
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    uid             BIGINT                DEFAULT NULL COMMENT '创建人',
    name            VARCHAR(128)          DEFAULT NULL COMMENT '嵌入名称',
    token           VARCHAR(64)  NOT NULL COMMENT '公开嵌入令牌',
    datasource_id   BIGINT       NOT NULL COMMENT '被开放的数据源（取多选列表首个，兼容旧数据）',
    datasource_ids  VARCHAR(1024)         DEFAULT NULL COMMENT '被开放的数据源 id 列表，逗号分隔（多选）',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    expire_at       DATETIME              DEFAULT NULL COMMENT '过期时间（空=不过期）',
    allowed_origins VARCHAR(512)          DEFAULT NULL COMMENT '允许嵌入的站点来源，逗号分隔；空=不限制',
    view_count      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_embed_token (token),
    KEY idx_chat_embed_tenant (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话问数嵌入：第三方页面匿名嵌入令牌';

-- 大模型配置（全局默认 tenant_id=NULL + 租户覆盖；列入 IGNORE 名单，按显式条件解析而非自动注入）
CREATE TABLE IF NOT EXISTS llm_config
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT                 DEFAULT NULL COMMENT 'NULL=全局默认；非空=租户专属覆盖',
    provider        VARCHAR(32)   NOT NULL COMMENT 'DEEPSEEK/QWEN/OPENAI_COMPATIBLE',
    name            VARCHAR(128)  NOT NULL COMMENT '配置显示名',
    base_url        VARCHAR(255)  NOT NULL COMMENT 'OpenAI 兼容 base-url',
    api_key_cipher  VARCHAR(1024)          DEFAULT NULL COMMENT 'AES-GCM 密文，绝不明文',
    model           VARCHAR(128)  NOT NULL,
    timeout_seconds INT           NOT NULL DEFAULT 60,
    priority        INT           NOT NULL DEFAULT 100 COMMENT '越小越优先（主力→备选）',
    enabled         TINYINT       NOT NULL DEFAULT 1,
    extra_body      VARCHAR(1000)          DEFAULT NULL COMMENT '额外请求体 JSON（逐字段并入请求体顶层），如 {"thinking":{"type":"disabled"}}',
    max_tokens      INT                    DEFAULT NULL COMMENT '输出 token 上限；空=用供应商默认',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_priority (tenant_id, priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='大模型配置';

-- 每日配额用量（租户隔离表，按天计数，持久化、支持重启与多实例）
CREATE TABLE IF NOT EXISTS quota_usage
(
    id         BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT NOT NULL,
    usage_date DATE   NOT NULL COMMENT '用量归属日',
    used       INT    NOT NULL DEFAULT 0 COMMENT '当日已用次数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_date (tenant_id, usage_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日配额用量';

-- 指标（租户隔离表）：把问数结果保存为可复用图表指标，刷新时直接重跑已存 SQL（不消耗 LLM）
CREATE TABLE IF NOT EXISTS metric
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT        NOT NULL,
    uid           BIGINT        NOT NULL COMMENT '创建者',
    name          VARCHAR(128)  NOT NULL,
    description   VARCHAR(500)           DEFAULT NULL,
    alias        VARCHAR(128)          DEFAULT NULL COMMENT '指标展示别名（图表标题/大屏组件默认名；空=回落 name）',
    definition    VARCHAR(512)          DEFAULT NULL COMMENT 'canonical 指标口径表达式（如 SUM(amount)）；趋势/因子/归因共用，空=未沉淀',
    attribution_dimension VARCHAR(64)   DEFAULT NULL COMMENT '归因默认维度（物理列名）；空=由用户选择',
    baseline_type VARCHAR(16)           DEFAULT NULL COMMENT '归因默认对比基准 MOM/YOY/PEAK；空=回落 MOM',
    -- 新鲜度（P2）：refreshed_at 只由「成功取数」写入，不能用 updated_at 代替（后者改别名也会刷新）
    refreshed_at  DATETIME              DEFAULT NULL COMMENT '数据最后成功刷新时间；空=从未刷新',
    freshness_hours INT                 DEFAULT NULL COMMENT '该指标新鲜度 SLA（小时）；空=回落全局 FRESHNESS/sla-hours',
    datasource_id BIGINT        NOT NULL,
    question      VARCHAR(500)           DEFAULT NULL COMMENT '原始自然语言问题',
    sql_text      TEXT COMMENT '已校验的安全 SELECT',
    chart_spec    VARCHAR(1024)          DEFAULT NULL COMMENT '图表规格 JSON',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_uid (tenant_id, uid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='保存的图表指标';

-- 驾驶舱/大屏（租户隔离表）：layout 为 vue-grid 网格 JSON [{i,metricId,x,y,w,h,title}]
CREATE TABLE IF NOT EXISTS dashboard
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    uid             BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(500)          DEFAULT NULL,
    template_code   VARCHAR(64)           DEFAULT NULL COMMENT '来源模板（空=空白/自动）',
    group_id        BIGINT                DEFAULT NULL COMMENT '所属分组（dashboard_group.id，空=未分组）',
    theme           VARCHAR(32)  NOT NULL DEFAULT 'light' COMMENT '主题 key（预设多套：dark/light/klein-blue/...）',
    cols            INT          NOT NULL DEFAULT 12 COMMENT '网格列数',
    refresh_seconds INT          NOT NULL DEFAULT 0 COMMENT '0=不自动刷新',
    is_screen       TINYINT      NOT NULL DEFAULT 0 COMMENT '1=作为大屏展示',
    layout          MEDIUMTEXT COMMENT 'widget 网格布局 JSON',
    background      VARCHAR(512) DEFAULT NULL COMMENT '大屏自定义背景（CSS background：色值/渐变/url()；空=跟随主题）',
    style_config    MEDIUMTEXT COMMENT '大屏级自定义样式 JSON（卡片/字体/配色覆盖；空=完全跟随主题）',
    share_token     VARCHAR(64)           DEFAULT NULL COMMENT '大屏公开分享令牌（空=未分享）',
    share_password  VARCHAR(64)           DEFAULT NULL COMMENT '大屏分享访问密码（BCrypt 哈希，可空）',
    share_expire_at DATETIME              DEFAULT NULL COMMENT '大屏分享过期时间（空=不过期）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_uid (tenant_id, uid),
    KEY idx_share_token (share_token)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='驾驶舱/大屏';

-- 资源分组（租户隔离表）：**驾驶舱与指标库共用的**多层级目录树，parent_id 空=根级。
-- resource_type 分域（DASHBOARD / METRIC）——两种资源各需要一棵独立的树，共用一张表避免两份实现漂移。
-- dashboard.group_id / metric.group_id 均引用本表（且只应引用同 resource_type 的行）。
CREATE TABLE IF NOT EXISTS resource_group
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    uid           BIGINT      NOT NULL COMMENT '创建者',
    resource_type VARCHAR(32) NOT NULL COMMENT '分组域：DASHBOARD / METRIC（两棵树彼此独立）',
    name          VARCHAR(64) NOT NULL,
    parent_id     BIGINT               DEFAULT NULL COMMENT '父分组 id（空=根级）',
    sort          INT         NOT NULL DEFAULT 0 COMMENT '同级展示顺序',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_type_parent (tenant_id, resource_type, parent_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='资源分组目录树（驾驶舱/指标库共用）';

-- 驾驶舱内置模板（全局表，列入 IGNORE 名单）：layout 为含槽位占位的骨架布局
CREATE TABLE IF NOT EXISTS dashboard_template
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL COMMENT '模板唯一编码',
    name        VARCHAR(128) NOT NULL,
    category    VARCHAR(64)           DEFAULT NULL,
    description VARCHAR(500)          DEFAULT NULL,
    icon        VARCHAR(64)           DEFAULT NULL COMMENT '前端图标名（封面兜底）',
    theme       VARCHAR(32)  NOT NULL DEFAULT 'dark',
    layout      MEDIUMTEXT COMMENT '骨架布局 JSON（绑定指标后成为 dashboard.layout）',
    builtin     TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='驾驶舱内置模板';

-- =============================================================
-- 配置化架构升级（V2）：迁移注册表 / 统一配置中心 / 语义层 / 样例库 / 图表规则 / 主题令牌
-- =============================================================

-- Schema 迁移注册表（全局表，列入 IGNORE 名单）：记录已应用的迁移 key，保证老库幂等补齐
CREATE TABLE IF NOT EXISTS schema_migration
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    migration_key VARCHAR(64) NOT NULL COMMENT '迁移唯一标识（有序执行、登记幂等）',
    applied_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_migration_key (migration_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Schema 迁移注册表';

-- 统一配置中心（全局表，列入 IGNORE 名单）：scope 二级（GLOBAL 默认 / TENANT 覆盖），
-- category 分组（RESULT 问数结果展示 / STYLE 样式 / CHART 图表 / NL2SQL 引擎策略 / SYSTEM），
-- value_json 存任意 JSON 标量/对象/数组；服务层按 TENANT→GLOBAL→代码默认 三级回退解析；
-- GLOBAL 行的 managed=1 时该键升格为「平台管控」：忽略并拒绝 TENANT 覆盖（超管统一控制所有租户）
CREATE TABLE IF NOT EXISTS sys_config
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type  VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/TENANT',
    scope_id    BIGINT                DEFAULT NULL COMMENT 'TENANT 时为租户 id；GLOBAL 为 NULL',
    category    VARCHAR(32)  NOT NULL COMMENT 'RESULT/STYLE/CHART/NL2SQL/SYSTEM',
    config_key  VARCHAR(128) NOT NULL,
    value_json  MEDIUMTEXT COMMENT '配置值 JSON',
    description VARCHAR(255)          DEFAULT NULL,
    version     INT          NOT NULL DEFAULT 1 COMMENT '乐观版本号，每次更新 +1',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    -- 平台管控：仅对 GLOBAL 行有意义。1=该 (category,config_key) 锁死为平台值，忽略并拒绝 TENANT 覆盖
    managed     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_cat_key (scope_type, scope_id, category, config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='统一配置中心';

-- 语义层（租户隔离表）：表/列的业务语义元数据（中文别名/描述/同义词/角色/枚举/业务口径），
-- 注入 NL2SQL Prompt、SchemaLinker 打分与图表角色判定，提升问数与图表准确率
CREATE TABLE IF NOT EXISTS semantic_meta
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    datasource_id BIGINT       NOT NULL,
    object_type   VARCHAR(16)  NOT NULL COMMENT 'TABLE/COLUMN',
    table_name    VARCHAR(128) NOT NULL,
    column_name   VARCHAR(128)          DEFAULT NULL COMMENT 'COLUMN 时非空',
    alias_cn      VARCHAR(128)          DEFAULT NULL COMMENT '中文业务别名',
    description   VARCHAR(500)          DEFAULT NULL COMMENT '业务描述/口径说明',
    synonyms      VARCHAR(500)          DEFAULT NULL COMMENT '同义词，逗号分隔',
    column_role   VARCHAR(16)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/DIMENSION/MEASURE/TEMPORAL',
    enum_values   TEXT COMMENT '枚举值 JSON 数组（采样回填）',
    enum_labels   VARCHAR(2000)      DEFAULT NULL COMMENT '枚举字典 JSON 对象（码值→中文标签，如 {"1":"已支付"}）；空=未配置字典',
    sql_expr      VARCHAR(500)          DEFAULT NULL COMMENT '业务口径表达式（如 unit_price*quantity）',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    `sensitive`   TINYINT      DEFAULT NULL COMMENT '是否敏感列：1=需角色可见；空/null=普通（无字段级脱敏）',
    `visible_roles` VARCHAR(255) DEFAULT NULL COMMENT '允许查看的角色码（逗号分隔，如 OWNER,ADMIN,DATA_STEWARD）；空=仅 OWNER/ADMIN 可见',
    `source`       VARCHAR(16)  DEFAULT NULL COMMENT '来源标记：MANUAL/SKELETON/AI（AI 自动分析识别）；空=人工维护',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_ds_table (tenant_id, datasource_id, table_name),
    UNIQUE KEY uk_ds_obj (tenant_id, datasource_id, table_name, column_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='语义层元数据';

-- 表关联登记（租户隔离表）：人工维护的"哪两张表用哪对键 JOIN"权威口径。
-- 语义层原本只有表级与列级两级语义，缺第三级"关系语义"：库中没建外键时（业务库与分析型库的常态）
-- LLM 只能自造连接键，一对多关联后直接求和会造成金额翻倍这类"SQL 跑通、结果非空、数值错"的静默错数。
-- 本表的行以最高可信度并入 JoinGraph（覆盖同键的外键与命名推断结果），并可用于声明推不出来的多对多。
CREATE TABLE IF NOT EXISTS table_relation
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    datasource_id BIGINT       NOT NULL,
    left_table    VARCHAR(128) NOT NULL COMMENT '多方表名（持有连接键的一侧）',
    left_column   VARCHAR(128) NOT NULL COMMENT '多方连接列',
    right_table   VARCHAR(128) NOT NULL COMMENT '一方表名',
    right_column  VARCHAR(128) NOT NULL COMMENT '一方连接列',
    relation_type VARCHAR(16)  NOT NULL DEFAULT 'MANY_TO_ONE' COMMENT 'MANY_TO_ONE/ONE_TO_ONE/MANY_TO_MANY',
    description   VARCHAR(500)          DEFAULT NULL COMMENT '业务口径说明（为何这样关联）',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tr_tenant_ds (tenant_id, datasource_id),
    UNIQUE KEY uk_tr_pair (tenant_id, datasource_id, left_table, left_column, right_table, right_column)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='表关联登记（人工维护的连接键口径）';

-- 问数样例库（租户隔离表）：人工维护的 问法→SQL  curated 样例 + 历史晋升样例，
-- 与 query_history 合并参与少样本检索，形成持续学习闭环
CREATE TABLE IF NOT EXISTS few_shot_example
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    datasource_id BIGINT       NOT NULL,
    question      VARCHAR(500) NOT NULL,
    sql_text      TEXT         NOT NULL,
    intent        VARCHAR(32)           DEFAULT NULL COMMENT '意图标注（可空）',
    tags          VARCHAR(255)          DEFAULT NULL COMMENT '标签，逗号分隔',
    source        VARCHAR(24)  NOT NULL DEFAULT 'CURATED' COMMENT 'CURATED/HISTORY_PROMOTED',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    use_count     INT          NOT NULL DEFAULT 0 COMMENT '被少样本命中次数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问数样例库';

-- 业务术语词典（租户隔离表）：路由前把用户口语词（term，如"流水"）归一为规范名（canonical，如"营收"），
-- 提升指标直达/样例复用/数据字典问答等免 LLM 技能命中率；按租户+数据源维度维护，避免跨源串义
CREATE TABLE IF NOT EXISTS term_map
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    datasource_id BIGINT       NOT NULL,
    term          VARCHAR(128) NOT NULL COMMENT '用户口语词',
    canonical     VARCHAR(128) NOT NULL COMMENT '规范名',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='业务术语词典';

-- 图表推荐规则（全局表，列入 IGNORE 名单）：KEYWORD 行=关键词→图型（priority 越小越优先），
-- THRESHOLD 行=结构阈值（pattern=key，priority=值）；租户可覆盖全局（tenant_id 非空优先）
CREATE TABLE IF NOT EXISTS chart_rule
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT                DEFAULT NULL COMMENT 'NULL=全局规则；非空=租户覆盖',
    rule_type   VARCHAR(16)  NOT NULL COMMENT 'KEYWORD/THRESHOLD',
    pattern     VARCHAR(255) NOT NULL COMMENT 'KEYWORD=问题关键词；THRESHOLD=阈值键',
    chart_type  VARCHAR(16)           DEFAULT NULL COMMENT 'KEYWORD 行目标图型；THRESHOLD 行为空',
    priority    INT          NOT NULL DEFAULT 100 COMMENT 'KEYWORD=匹配优先级；THRESHOLD=阈值值',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    description VARCHAR(255)          DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_type (tenant_id, rule_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='图表推荐规则';

-- 主题令牌（全局表，列入 IGNORE 名单）：样式配置化事实源；tokens 与前端 DashboardTheme 同构 JSON，
-- tenant_id 非空为租户自定义主题；builtin=1 为内置预设（DataSeeder 播种）
CREATE TABLE IF NOT EXISTS theme_config
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT                DEFAULT NULL COMMENT 'NULL=全局预设；非空=租户自定义',
    code       VARCHAR(64)  NOT NULL COMMENT '主题唯一编码（dashboard.theme 引用）',
    name       VARCHAR(128) NOT NULL,
    dark       TINYINT      NOT NULL DEFAULT 0 COMMENT '1=深色系',
    tokens     MEDIUMTEXT   NOT NULL COMMENT '主题令牌 JSON（palette/axis/tooltip/text/background...）',
    builtin    TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='主题令牌配置';

-- 用户手册（平台级全局档，列入 IGNORE 名单）：超管在后台编辑草稿并发布，全体用户阅读发布版。
-- 采用“草稿(content) + 已发布快照(published_content)”双列：编辑不影响线上，显式发布才生效。
-- 全库单例（只一行，由 DataSeeder 播种）；published_content 为 NULL 表示尚未发布，用户端读到空。
CREATE TABLE IF NOT EXISTS user_manual
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    title             VARCHAR(128) NOT NULL DEFAULT 'AI 数据助理用户手册' COMMENT '草稿标题',
    content           MEDIUMTEXT            COMMENT '草稿正文（Markdown）',
    published_title   VARCHAR(128)          DEFAULT NULL COMMENT '已发布标题',
    published_content MEDIUMTEXT            COMMENT '已发布正文（用户可见；NULL=尚未发布）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT=有未发布改动 / PUBLISHED=草稿与发布版一致',
    version           INT          NOT NULL DEFAULT 0 COMMENT '发布版本号（每次发布 +1）',
    updated_by        BIGINT                DEFAULT NULL COMMENT '最近编辑人 uid',
    published_at      DATETIME              DEFAULT NULL COMMENT '最近发布时间',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户手册（平台级全局单例）';

-- =============================================================
-- RBAC 与组织协同（V2026_09，共 14 表）
-- 见《AI 数据助理-角色权限体系与组织协同设计》5
--
-- 隔离要点：
--   1) 全局字典表（user_identity/role/permission/role_permission）无 tenant_id 概念；
--   2) team_member / user_role 虽带 tenant_id，但登录链路需在租户上下文建立之前读取，
--      故与 app_user 同理必须列入 MybatisPlusConfig.IGNORE_TABLES，否则 tenant_id=NULL
--      恒不命中 → 登录后角色为空集 → 全站 403；列入后其读写必须显式带 tenant_id 条件；
--   3) invitation / invitation_apply 列入 IGNORE 是为了支持匿名接受邀请（尚无租户上下文）。
-- =============================================================

-- 自然人身份（全局表，IGNORE）：口令与联系方式的唯一事实源；一人可在多个组织各有 app_user 身份
CREATE TABLE IF NOT EXISTS user_identity
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(128)          DEFAULT NULL COMMENT '唯一（NULL 可重复）',
    phone         VARCHAR(32)           DEFAULT NULL COMMENT '唯一（NULL 可重复）',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt，绝不明文',
    nickname      VARCHAR(64)           DEFAULT NULL,
    avatar        VARCHAR(512)          DEFAULT NULL,
    auth_source   VARCHAR(32)  NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL/SSO/LDAP/WECOM（本期仅 LOCAL）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=封禁（全组织失效）',
    mfa_enabled   TINYINT      NOT NULL DEFAULT 0 COMMENT '预留：双因素',
    last_login_at DATETIME              DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='自然人身份';

-- 角色字典（全局表，IGNORE）：内置角色由 DataSeeder 逐条播种（builtin=1，不可删）
CREATE TABLE IF NOT EXISTS role
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    code             VARCHAR(32) NOT NULL COMMENT '角色码，全局唯一',
    name             VARCHAR(64) NOT NULL,
    level            VARCHAR(16) NOT NULL COMMENT 'PLATFORM/TENANT/TEAM',
    data_scope       VARCHAR(24) NOT NULL DEFAULT 'SELF' COMMENT 'ALL/TENANT/TEAM_AND_CHILD/TEAM/SELF/CUSTOM',
    inherit_to_child TINYINT     NOT NULL DEFAULT 0 COMMENT '1=团队角色对子团队生效',
    builtin          TINYINT     NOT NULL DEFAULT 1,
    description      VARCHAR(500)         DEFAULT NULL,
    sort             INT         NOT NULL DEFAULT 0,
    enabled          TINYINT     NOT NULL DEFAULT 1,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_level (level)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色字典';

-- 权限点字典（全局表，IGNORE）：前端菜单/按钮与后端 API 的唯一事实源
CREATE TABLE IF NOT EXISTS permission
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(96)  NOT NULL COMMENT 'module:resource:action',
    name        VARCHAR(128) NOT NULL,
    module      VARCHAR(32)  NOT NULL,
    level       VARCHAR(16)  NOT NULL DEFAULT 'TENANT' COMMENT 'PLATFORM/TENANT/TEAM/RESOURCE',
    type        VARCHAR(16)  NOT NULL DEFAULT 'API' COMMENT 'MENU/BUTTON/API/DATA',
    menu_code   VARCHAR(64)           DEFAULT NULL COMMENT 'MENU 型：前端路由 name',
    menu_path   VARCHAR(255)          DEFAULT NULL COMMENT 'MENU 型：前端路径（仅展示用）',
    menu_icon   VARCHAR(64)           DEFAULT NULL,
    parent_code VARCHAR(96)           DEFAULT NULL COMMENT '权限点分组（后台树形展示用）',
    api_method  VARCHAR(16)           DEFAULT NULL COMMENT 'API 型：GET/POST/PUT/DELETE',
    api_pattern VARCHAR(255)          DEFAULT NULL COMMENT 'API 型：/api/datasource/**',
    builtin     TINYINT      NOT NULL DEFAULT 1,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    sort        INT          NOT NULL DEFAULT 0,
    description VARCHAR(500)          DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_module_type (module, type),
    KEY idx_parent (parent_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='权限点字典';

-- 角色→权限点（全局表，IGNORE）：内置角色的授权事实源
CREATE TABLE IF NOT EXISTS role_permission
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    role_code       VARCHAR(32) NOT NULL,
    permission_code VARCHAR(96) NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_code, permission_code),
    KEY idx_permission (permission_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色权限关联';

-- 角色授予（租户隔离表，但列入 IGNORE：登录链路需在租户上下文之前读取）
CREATE TABLE IF NOT EXISTS user_role
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL COMMENT '平台角色为 0',
    uid            BIGINT      NOT NULL COMMENT 'app_user.id',
    role_code      VARCHAR(32) NOT NULL,
    scope_type     VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'PLATFORM/TENANT/TEAM',
    scope_id       BIGINT      NOT NULL DEFAULT 0 COMMENT 'TEAM 时为 team_id；其余为 0',
    custom_role_id BIGINT               DEFAULT NULL COMMENT '非空=授予的是租户自定义角色',
    granted_by     BIGINT               DEFAULT NULL,
    expire_at      DATETIME             DEFAULT NULL COMMENT '临时授权到期时间（空=永久）',
    status         TINYINT     NOT NULL DEFAULT 1,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uid_role_scope (uid, role_code, scope_type, scope_id),
    KEY idx_tenant_uid (tenant_id, uid),
    KEY idx_scope (scope_type, scope_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色授予';

-- 租户自定义角色（租户隔离表）：派生自内置角色，权限可窄化不可宽于 base
CREATE TABLE IF NOT EXISTS custom_role
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    name           VARCHAR(64) NOT NULL,
    base_role_code VARCHAR(32) NOT NULL COMMENT '派生自哪个内置角色',
    data_scope     VARCHAR(24) NOT NULL DEFAULT 'TEAM' COMMENT '可窄化，不可宽于 base',
    description    VARCHAR(500)         DEFAULT NULL,
    enabled        TINYINT     NOT NULL DEFAULT 1,
    created_by     BIGINT               DEFAULT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_name (tenant_id, name),
    KEY idx_tenant_base (tenant_id, base_role_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='租户自定义角色';

-- 自定义角色的权限增减（租户隔离表）：DENY 优先且不可被 base 角色的 GRANT 反转
CREATE TABLE IF NOT EXISTS role_permission_override
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    custom_role_id  BIGINT      NOT NULL,
    permission_code VARCHAR(96) NOT NULL,
    effect          VARCHAR(8)  NOT NULL COMMENT 'GRANT/DENY',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (custom_role_id, permission_code),
    KEY idx_tenant_role (tenant_id, custom_role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='自定义角色权限增减';

-- 数据权限 A（v1）：成员直授 · 数据源可见性（黑名单：无行=可见，effect=HIDDEN 才隐藏；ALLOW 显式放行）
CREATE TABLE IF NOT EXISTS user_datasource_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    uid           BIGINT      NOT NULL COMMENT 'app_user.id',
    datasource_id BIGINT      NOT NULL,
    effect        VARCHAR(8)  NOT NULL COMMENT 'HIDDEN/ALLOW',
    granted_by    BIGINT               DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_ds (tenant_id, uid, datasource_id),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='成员数据源可见性';

-- 数据权限 A（v1）：成员直授 · 字段脱敏（白名单：无行=按语义层 sensitive 默认掩码；UNMASK=显式放行）
CREATE TABLE IF NOT EXISTS user_field_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    uid           BIGINT      NOT NULL COMMENT 'app_user.id',
    datasource_id BIGINT      NOT NULL,
    column_name   VARCHAR(128) NOT NULL,
    table_name    VARCHAR(128)          DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列',
    effect        VARCHAR(8)  NOT NULL COMMENT 'MASK/UNMASK',
    granted_by    BIGINT               DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_col (tenant_id, uid, datasource_id, table_name, column_name),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='成员字段脱敏';

-- 数据权限 A（v1）：角色继承版（挂在 custom_role 上，语义同上）
CREATE TABLE IF NOT EXISTS role_datasource_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    custom_role_id BIGINT     NOT NULL,
    datasource_id BIGINT      NOT NULL,
    effect        VARCHAR(8)  NOT NULL COMMENT 'HIDDEN/ALLOW',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_ds (tenant_id, custom_role_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色数据源可见性';

CREATE TABLE IF NOT EXISTS role_field_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    custom_role_id BIGINT     NOT NULL,
    datasource_id BIGINT      NOT NULL,
    column_name   VARCHAR(128) NOT NULL,
    table_name    VARCHAR(128)          DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列',
    effect        VARCHAR(8)  NOT NULL COMMENT 'MASK/UNMASK',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_col (tenant_id, custom_role_id, datasource_id, table_name, column_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色字段脱敏';

-- 数据权限 A（v2）：表级可见性——补齐"数据授权无表维度"。模型与数据源可见性一致（黑名单：无行=可见，HIDDEN 才隐藏）。
-- 生效两层：① schema 层省略被隐藏表（LLM 看不到）；② SQL 执行前拦截（防手填 SQL 绕过）。两者取最宽（ALLOW 胜 HIDDEN）。
CREATE TABLE IF NOT EXISTS user_table_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    uid           BIGINT      NOT NULL COMMENT 'app_user.id',
    datasource_id BIGINT      NOT NULL,
    table_name    VARCHAR(128) NOT NULL COMMENT '表名（库前缀可选）',
    effect        VARCHAR(8)  NOT NULL COMMENT 'HIDDEN/ALLOW',
    granted_by    BIGINT               DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tbl (tenant_id, uid, datasource_id, table_name),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='成员表级可见性';

CREATE TABLE IF NOT EXISTS role_table_acl
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    custom_role_id BIGINT     NOT NULL,
    datasource_id BIGINT      NOT NULL,
    table_name    VARCHAR(128) NOT NULL COMMENT '表名（库前缀可选）',
    effect        VARCHAR(8)  NOT NULL COMMENT 'HIDDEN/ALLOW',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tbl (tenant_id, custom_role_id, datasource_id, table_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色表级可见性';

-- 团队（租户隔离表）：组织内的部门/项目组，支持 parent_id 树形；资源归属与可见性收敛单位
CREATE TABLE IF NOT EXISTS team
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL COMMENT '所属组织，隔离键',
    parent_id   BIGINT                DEFAULT NULL COMMENT '父团队（NULL=顶级）',
    name        VARCHAR(128) NOT NULL,
    code        VARCHAR(64)  NOT NULL COMMENT '组织内唯一编码；default=默认团队，不可删改',
    owner_uid   BIGINT                DEFAULT NULL COMMENT '团队负责人 uid',
    description VARCHAR(500)          DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_id, code),
    KEY idx_tenant_parent (tenant_id, parent_id),
    KEY idx_tenant_owner (tenant_id, owner_uid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='团队';

-- 团队成员（租户隔离表，但列入拦截器 IGNORE：登录链路须在租户上下文之前读取）
CREATE TABLE IF NOT EXISTS team_member
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT      NOT NULL,
    team_id   BIGINT      NOT NULL,
    uid       BIGINT      NOT NULL COMMENT 'app_user.id',
    team_role VARCHAR(32) NOT NULL DEFAULT 'TEAM_VIEWER' COMMENT 'TEAM_OWNER/TEAM_EDITOR/TEAM_VIEWER',
    status    TINYINT     NOT NULL DEFAULT 1 COMMENT '1=正常 0=已移除（保留行以留痕）',
    joined_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invited_by BIGINT              DEFAULT NULL COMMENT '邀请人 uid',
    left_at   DATETIME             DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_uid (tenant_id, team_id, uid),
    KEY idx_tenant_uid (tenant_id, uid),
    KEY idx_team_status (team_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='团队成员';

-- 资源级 ACL（租户隔离表）：跨团队共享与逐资源授权，DataScope=CUSTOM 时据此判定
CREATE TABLE IF NOT EXISTS resource_acl
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    resource_type  VARCHAR(24) NOT NULL COMMENT 'DATASOURCE/METRIC/DASHBOARD/SHARE/TEAM',
    resource_id    BIGINT      NOT NULL,
    principal_type VARCHAR(16) NOT NULL COMMENT 'USER/TEAM/ROLE/CUSTOM_ROLE',
    principal_id   BIGINT      NOT NULL COMMENT 'USER=uid；TEAM=team_id；ROLE/CUSTOM_ROLE=角色 id',
    principal_code VARCHAR(32)          DEFAULT NULL COMMENT 'ROLE 时存角色码，便于按码判定',
    permission     VARCHAR(16) NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE/MANAGE',
    granted_by     BIGINT               DEFAULT NULL,
    expire_at      DATETIME             DEFAULT NULL,
    status         TINYINT     NOT NULL DEFAULT 1,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_res_principal (tenant_id, resource_type, resource_id, principal_type, principal_id),
    KEY idx_principal (tenant_id, principal_type, principal_id),
    KEY idx_resource (tenant_id, resource_type, resource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='资源级授权';

-- 邀请（租户隔离表，列入 IGNORE 以支持匿名接受）：token 只存 SHA-256 哈希，明文仅在生成时返回一次
CREATE TABLE IF NOT EXISTS invitation
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL,
    team_id          BIGINT                DEFAULT NULL COMMENT '入组后归属团队（空=默认团队）',
    channel          VARCHAR(16)  NOT NULL DEFAULT 'LINK' COMMENT 'LINK/EMAIL/SMS/BATCH/APPLY',
    token_hash       CHAR(64)     NOT NULL COMMENT 'SHA-256(invite_token) 十六进制',
    target_email     VARCHAR(128)          DEFAULT NULL COMMENT '定向邀请：仅该邮箱可接受',
    target_phone     VARCHAR(32)           DEFAULT NULL,
    allow_domain     VARCHAR(128)          DEFAULT NULL COMMENT '链接邀请：限定邮箱域（空=不限）',
    role_code        VARCHAR(32)  NOT NULL DEFAULT 'VIEWER' COMMENT '接受后授予的组织角色',
    team_role        VARCHAR(32)           DEFAULT NULL COMMENT '接受后授予的团队角色',
    custom_role_id   BIGINT                DEFAULT NULL,
    seats            INT          NOT NULL DEFAULT 1 COMMENT '可接受次数上限（-1=不限）',
    used_count       INT          NOT NULL DEFAULT 0,
    require_approval TINYINT      NOT NULL DEFAULT 0 COMMENT '1=接受后进入待审批',
    need_login       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=须登录后接受（防匿名占席）',
    expire_at        DATETIME              DEFAULT NULL COMMENT '空=不过期',
    status           VARCHAR(16)  NOT NULL DEFAULT 'SENT'
        COMMENT 'DRAFT/SENT/PENDING/APPROVED/REJECTED/ACCEPTED/EXPIRED/REVOKED',
    invited_by       BIGINT       NOT NULL,
    message          VARCHAR(500)          DEFAULT NULL COMMENT '邀请附言',
    notify_status    VARCHAR(16)           DEFAULT NULL COMMENT '通知发送结果 PENDING/SENT/FAILED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_target_email (target_email),
    KEY idx_expire (status, expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='组织邀请';

-- 申请加入（租户隔离表，列入 IGNORE）：用户凭组织码/链接发起，管理员审批
CREATE TABLE IF NOT EXISTS invitation_apply
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    invitation_id BIGINT                DEFAULT NULL COMMENT '来源邀请（可空=凭组织码裸申请）',
    team_id       BIGINT                DEFAULT NULL,
    uid           BIGINT                DEFAULT NULL COMMENT '已注册身份的 uid',
    identity_id   BIGINT                DEFAULT NULL,
    applicant     VARCHAR(128) NOT NULL COMMENT '申请人邮箱/手机/用户名',
    reason        VARCHAR(500)          DEFAULT NULL,
    role_code     VARCHAR(32)  NOT NULL DEFAULT 'VIEWER',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED/EXPIRED',
    reviewed_by   BIGINT                DEFAULT NULL,
    review_note   VARCHAR(500)          DEFAULT NULL,
    reviewed_at   DATETIME              DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_applicant (applicant)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入组申请';

-- 权限与登录审计（租户隔离表；平台域查询走 @InterceptorIgnore + 显式条件）
CREATE TABLE IF NOT EXISTS auth_audit_log
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '0=平台域事件',
    uid           BIGINT                DEFAULT NULL COMMENT '操作人（匿名事件为空）',
    identity_id   BIGINT                DEFAULT NULL,
    actor_type    VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/OPERATOR/SUPPORT/SYSTEM',
    action        VARCHAR(48)  NOT NULL
        COMMENT 'LOGIN/LOGOUT/LOGIN_FAILED/PERM_CHECK_DENIED/ROLE_GRANT/ROLE_REVOKE/MEMBER_ADD/MEMBER_REMOVE/INVITE_CREATE/INVITE_ACCEPT/INVITE_REVOKE/OWNER_TRANSFER/ASSIST_GRANT/RESOURCE_ACL_CHANGE/PASSWORD_RESET/TENANT_DISABLE',
    resource_type VARCHAR(32)           DEFAULT NULL,
    resource_id   VARCHAR(64)           DEFAULT NULL,
    target_uid    BIGINT                DEFAULT NULL COMMENT '被操作人',
    result        VARCHAR(16)  NOT NULL DEFAULT 'OK' COMMENT 'OK/DENIED/FAILED',
    detail        VARCHAR(1000)         DEFAULT NULL COMMENT 'JSON：变更前后值、权限点、角色码等（脱敏，禁存口令与凭证）',
    ip            VARCHAR(64)           DEFAULT NULL,
    user_agent    VARCHAR(512)          DEFAULT NULL,
    trace_id      VARCHAR(64)           DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, created_at),
    KEY idx_uid_time (uid, created_at),
    KEY idx_action (action, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='权限与登录审计';

-- 全局操作日志（跨租户表：请求级自动捕获，与权限/登录审计互补）
-- 查询范围由检索接口按身份显式收敛（平台后台跨租户 / 组织内钉死当前租户），故不随租户隔离。
CREATE TABLE IF NOT EXISTS audit_log
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（0=平台域）；仅作标记，不作隔离',
    user_id       BIGINT                DEFAULT NULL COMMENT '操作人 uid（匿名请求为空）',
    username      VARCHAR(64)           DEFAULT NULL COMMENT '操作人登录名',
    org_roles     VARCHAR(255)          DEFAULT NULL COMMENT '组织角色码（逗号分隔）',
    platform_roles VARCHAR(255)         DEFAULT NULL COMMENT '平台角色码（逗号分隔，仅平台域非空）',
    request_id    VARCHAR(64)           DEFAULT NULL COMMENT '请求追踪 id',
    module        VARCHAR(32)           DEFAULT NULL COMMENT '业务模块（org:metrics/chat/admin:audit...）',
    action        VARCHAR(96)           DEFAULT NULL COMMENT '可读动作：METHOD /path',
    method        VARCHAR(8)            DEFAULT NULL COMMENT 'HTTP 方法',
    uri           VARCHAR(512)          DEFAULT NULL COMMENT '请求路径（不含查询串）',
    query_string  VARCHAR(1024)         DEFAULT NULL COMMENT '原始查询串',
    request_body  MEDIUMTEXT            DEFAULT NULL COMMENT '请求体（脱敏+截断，禁存口令）',
    ip            VARCHAR(64)           DEFAULT NULL COMMENT '客户端 IP（X-Forwarded-For 首段）',
    user_agent    VARCHAR(512)          DEFAULT NULL COMMENT 'User-Agent',
    http_status   INT                   DEFAULT NULL COMMENT 'HTTP 状态码',
    error_msg     VARCHAR(512)          DEFAULT NULL COMMENT '异常信息（仅失败时）',
    duration_ms   BIGINT                DEFAULT NULL COMMENT '处理耗时（毫秒）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时刻',
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, created_at),
    KEY idx_user_time (user_id, created_at),
    KEY idx_module_time (module, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='全局操作日志';

-- 登录会话（租户隔离表）：强制下线、多端管理、代客协助影子令牌
-- 引入本表后 logout 必须真吊销（置 revoked_at），否则被移除成员可用旧令牌至自然过期（当前配置 120 分钟）
CREATE TABLE IF NOT EXISTS login_session
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL DEFAULT 0,
    uid        BIGINT      NOT NULL,
    sid        VARCHAR(64) NOT NULL COMMENT 'JWT claim: sid',
    token_hash CHAR(64)    NOT NULL COMMENT 'SHA-256(access_token)',
    scope      VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/ASSIST（代客协助）',
    ip         VARCHAR(64)          DEFAULT NULL,
    user_agent VARCHAR(512)         DEFAULT NULL,
    expire_at  DATETIME    NOT NULL,
    revoked_at DATETIME             DEFAULT NULL COMMENT '非空=已吊销',
    revoked_by BIGINT               DEFAULT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sid (sid),
    KEY idx_tenant_uid (tenant_id, uid, revoked_at),
    KEY idx_token_hash (token_hash),
    KEY idx_expire (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录会话';

-- 数据血缘边（需求 §3.3）：指标 ↔ 字段 ↔ 表 ↔ 看板 的轻量引用关系。
-- 方向：from 依赖/引用 to。例：metric→field(depends_on)、field→table(source)、dashboard→metric(references)。
-- 租户隔离：tenant_id 必填且所有查询显式带租户条件，不依赖拦截器表名单（漏配即跨租户泄漏）。
-- to_id 可空（表没有独立实体 id）；MySQL UNIQUE 允许 NULL 重复，故先删后插即可幂等。
CREATE TABLE IF NOT EXISTS lineage_edge
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL,
    from_type  VARCHAR(24)  NOT NULL COMMENT '起点类型：metric/field/table/dashboard/alert',
    from_id    BIGINT       NULL COMMENT '起点实体 id',
    from_name  VARCHAR(128) NULL COMMENT '起点展示名（冗余，避免读期 join）',
    to_type    VARCHAR(24)  NOT NULL COMMENT '终点类型',
    to_id      BIGINT       NULL COMMENT '终点实体 id（表无实体时为空）',
    to_name    VARCHAR(128) NULL COMMENT '终点展示名',
    relation   VARCHAR(32)  NOT NULL COMMENT 'depends_on/source/references',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lineage (tenant_id, from_type, from_id, to_type, to_id, relation),
    KEY idx_lineage_to (tenant_id, to_type, to_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据血缘边';

-- 站内信（租户隔离表）：预警命中时按规则 notify_target 指定的接收人逐一写入，每人一条。
-- 接收人只能是「规则指定用户」（alert_rule.notify_target 存 app_user.id 列表，逗号/分号分隔），
-- 不做租户全员广播。is_read 标记已读，天然按 (tenant_id, uid) 隔离——只能读到自己的信。
CREATE TABLE IF NOT EXISTS notify_message
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL COMMENT '所属租户，隔离键',
    uid        BIGINT       NOT NULL COMMENT '接收人 app_user.id',
    rule_id    BIGINT       NULL COMMENT '来源预警规则 id（NULL=非预警产生的站内信）',
    record_id  BIGINT       NULL COMMENT '来源预警触发记录 id（NULL=非预警产生）',
    severity   VARCHAR(16)  NULL COMMENT 'INFO/WARN/CRITICAL',
    title      VARCHAR(255) NULL COMMENT '站内信标题',
    content    TEXT         NULL COMMENT '站内信正文',
    is_read    TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notify_message_tenant_uid (tenant_id, uid),
    KEY idx_notify_message_tenant_unread (tenant_id, uid, is_read)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='站内信';

-- 嵌入问数·外部用户对话日志：匿名 iframe 嵌入时按嵌入方传入的 user 唯一值(?user=)归并每次问数，
-- 同时承担审计留痕与初始化历史回看两职责。每行记一次轮次(USER 问句/BOT 结论摘要)，
-- 同一次多轮对话共享 conversation_id（与问数主链路一致，便于续接）。按 (tenant_id, embed_id, ext_user) 隔离与检索。
CREATE TABLE IF NOT EXISTS chat_embed_log
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL COMMENT '所属租户，隔离键',
    embed_id       BIGINT       NOT NULL COMMENT 'chat_embed.id',
    token          VARCHAR(64)  NOT NULL COMMENT 'chat_embed.token',
    ext_user       VARCHAR(128) NULL COMMENT '嵌入方传入的外部用户唯一值(?user=)',
    conversation_id VARCHAR(64) NULL COMMENT '多轮会话 id（与问数主链路 conversationId 一致）',
    role           VARCHAR(16)  NOT NULL COMMENT 'USER/BOT',
    content        TEXT         NULL COMMENT '消息内容（用户问句/机器人结论摘要）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_embed_log_scope (tenant_id, embed_id, ext_user),
    KEY idx_chat_embed_log_created (tenant_id, embed_id, ext_user, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='嵌入问数-外部用户对话日志';

-- 对外 MCP 的个人访问令牌（PAT）：外部 AI 客户端凭 Bearer 调用 POST /mcp。
-- 只存 SHA-256 哈希与展示前缀，明文不落库；revoked_at 非空即失效，可即时吊销。
CREATE TABLE IF NOT EXISTS mcp_pat
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL COMMENT '所属组织（隔离键）',
    uid          BIGINT       NOT NULL COMMENT '令牌绑定用户 app_user.id',
    name         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '令牌名称',
    token_prefix VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '明文前缀，仅展示',
    token_hash   VARCHAR(128) NOT NULL COMMENT '明文 SHA-256 十六进制',
    scopes       VARCHAR(128) NULL COMMENT '授权范围，逗号分隔',
    expires_at   DATETIME     NULL COMMENT '过期时间，空=不过期',
    revoked_at   DATETIME     NULL COMMENT '非空=已吊销',
    last_used_at DATETIME     NULL COMMENT '最近一次调用时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_pat_hash (token_hash),
    KEY idx_mcp_pat_tenant (tenant_id, id),
    KEY idx_mcp_pat_uid (tenant_id, uid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对外MCP个人访问令牌';

-- SQL 编辑器保存的查询模板（高级查询 P1）。
-- 与 query_history 的区别：history 记"跑过什么"，saved_query 记"留下来复用什么"；
-- 模板走 :param 命名占位符，运行期由 PreparedStatement 绑定，绝不字符串拼接（防注入）。
CREATE TABLE IF NOT EXISTS saved_query
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '所属组织（隔离键）',
    uid           BIGINT       NOT NULL COMMENT '创建人 app_user.id',
    datasource_id BIGINT       NOT NULL COMMENT 'data_source.id',
    name          VARCHAR(64)  NOT NULL COMMENT '模板名称（同租户同人下唯一）',
    sql_template  TEXT         NOT NULL COMMENT '带 :param 命名占位符的 SQL 模板',
    param_names   VARCHAR(255) NULL COMMENT '参数名，逗号分隔（与 :param 出现顺序一致，供前端回填表单）',
    description   VARCHAR(200) NULL COMMENT '模板说明',
    spec          TEXT         NULL COMMENT '构造器规格 JSON（BuilderSpec；非空=来自可视化构造器）',
    visibility    VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性：PRIVATE=仅本人 / SHARED=组织共享（成员可查/可跑/可设定时）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_saved_query_name (tenant_id, uid, name),
    KEY idx_saved_query_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SQL 查询模板（高级查询保存的查询）';

-- 智能预警引擎：规则表 + 触发记录表，均为租户隔离表（自动注入 tenant_id）。
-- 原为 backend/sql/V20260914__alert.sql 手工脚本（不在 spring.sql.init 链路内，新环境零启动缺表，
-- 导致 V2026_09_alert_attribution 等迁移步每次报错跳过）；2026-10-24 T8.2 并入初始化集，手工脚本归档。
-- 列定义保持与已运行库（含 dev）完全同构；auto_attribution/attribution_dimension/notify_target/attribution
-- 等后续演进列由 SchemaMigration 幂等补齐，不在此预先内嵌（保持新老库收敛路径一致）。
-- 表缺失时预警引擎自动降级为「不触发」（不影响其他功能）。
CREATE TABLE IF NOT EXISTS alert_rule
(
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT         NOT NULL,
    name           VARCHAR(128)   NOT NULL,
    description    VARCHAR(512),
    metric_key     VARCHAR(64)    NOT NULL COMMENT '指标键，如 query.count.24h',
    operator       VARCHAR(8)     NOT NULL COMMENT 'GT/LT/GTE/LTE',
    threshold      DECIMAL(20, 4) NOT NULL,
    severity       VARCHAR(16)    NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
    enabled        TINYINT(1)     NOT NULL DEFAULT 1,
    notify_channel VARCHAR(16)    NOT NULL DEFAULT 'LOG' COMMENT 'LOG/EMAIL/SMS/WEBHOOK（当前仅 LOG 实现）',
    window_minutes INT            NOT NULL DEFAULT 1440 COMMENT '去重窗口（分钟）：窗口内已 FIRED 不再重复触发',
    created_by     BIGINT,
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_alert_rule_tenant (tenant_id),
    INDEX idx_alert_rule_tenant_enabled (tenant_id, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '智能预警规则';

CREATE TABLE IF NOT EXISTS alert_record
(
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT         NOT NULL,
    rule_id    BIGINT         NOT NULL,
    rule_name  VARCHAR(128),
    metric_key VARCHAR(64),
    value      DECIMAL(20, 4),
    operator   VARCHAR(8),
    threshold  DECIMAL(20, 4),
    severity   VARCHAR(16),
    status     VARCHAR(16)    NOT NULL DEFAULT 'FIRED' COMMENT 'FIRED/ACK/RESOLVED',
    message    VARCHAR(512),
    notified   TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否已通知（当前仅 LOG，通知即置 1）',
    created_at DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_alert_record_tenant (tenant_id),
    INDEX idx_alert_record_rule (rule_id),
    INDEX idx_alert_record_tenant_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '智能预警触发记录';

-- 待治理问题台账（T11-1 二期 B）：服务端持久化每个归一键+信号簇的处置态，
-- 转写/忽略后跨会话不再霸屏；支持定时推送去重（notified_at）与重新打开。
-- 唯一键含 key_hash（normalized_key 全长 SHA-256）防碰撞。租户拦截器自动注入 tenant_id。
CREATE TABLE IF NOT EXISTS governance_issue
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL COMMENT '所属组织（隔离键）',
    datasource_id  BIGINT       NOT NULL COMMENT 'data_source.id',
    `signal`       VARCHAR(32)  NOT NULL COMMENT 'GovernanceSignal 枚举名',
    normalized_key VARCHAR(512) NOT NULL COMMENT '归一键（截断至 512 展示/查询用）',
    key_hash       CHAR(64)     NOT NULL COMMENT 'normalized_key 全长 SHA-256 十六进制（唯一键参与列，防碰撞）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CONVERTED/DISMISSED',
    question       VARCHAR(512) NULL COMMENT '最近一次代表问法（推送/展示用）',
    occurrences    INT          NOT NULL DEFAULT 0 COMMENT '最近一次扫描出现次数',
    ref_type       VARCHAR(16)  NULL COMMENT '处置产出类型：METRIC/FEWSHOT',
    ref_id         BIGINT       NULL COMMENT '转写产出 id',
    dismiss_reason VARCHAR(255) NULL COMMENT '忽略原因',
    first_seen_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近被扫描命中',
    resolved_at    DATETIME     NULL COMMENT '转为处置态的时间',
    resolved_by    BIGINT       NULL COMMENT '处置人 uid',
    notified_at    DATETIME     NULL COMMENT '最近推送时间（去重闸）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gov_issue (tenant_id, datasource_id, key_hash, `signal`),
    KEY idx_gov_issue_tenant_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='待治理问题台账（T11-1 二期 B）';

-- 定时报表（T12-1）：把已存查询模板固化为日/周/小时定时任务，确定性重跑并推送站内信/邮件。
-- 执行永远以创建人（uid）身份上下文跑模板，模板被删/越权则 SKIPPED 不外发；
-- 执行流水不另建表——复用 query_record（runTemplate 天然带 template_id 埋点）。租户拦截器自动注入 tenant_id。
CREATE TABLE IF NOT EXISTS scheduled_report
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '所属组织（隔离键）',
    uid           BIGINT       NOT NULL COMMENT '创建人 app_user.id（执行身份锚点）',
    name          VARCHAR(64)  NOT NULL COMMENT '报表名（同租户同人唯一）',
    template_id   BIGINT       NOT NULL COMMENT '引用的 saved_query.id',
    params_json   VARCHAR(1000) NULL COMMENT '模板参数固定值 JSON（空参模板留空）',
    freq          VARCHAR(16)  NOT NULL COMMENT '预置频率：DAILY/WEEKLY/HOURLY',
    run_time      VARCHAR(5)   NULL COMMENT '执行时刻 HH:mm（DAILY/WEEKLY 用；HOURLY 忽略）',
    weekdays      VARCHAR(13)  NULL COMMENT 'ISO 周几集 1,3,5（WEEKLY 用）',
    channel       VARCHAR(16)  NOT NULL DEFAULT 'INAPP' COMMENT '推送渠道 INAPP/EMAIL/BOTH',
    inapp_uids    VARCHAR(500) NULL COMMENT '站内信接收人 user id 逗号分隔（空=只发创建人）',
    email_targets VARCHAR(500) NULL COMMENT '邮件收件人（EMAIL/BOTH 必填）',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用',
    last_run_at   DATETIME     NULL COMMENT '上轮执行时刻（到期判定与幂等锚点）',
    last_status   VARCHAR(16)  NULL COMMENT 'OK/FAILED/SKIPPED',
    last_error    VARCHAR(500) NULL COMMENT '失败摘要（不含收件人）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scheduled_report_name (tenant_id, uid, name),
    KEY idx_scheduled_report_enabled (tenant_id, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='定时报表任务（T12-1）';

-- 结构变更感知快照（T12-3）：每数据源一行「上轮扫到的表/列集合」序列化，
-- 下轮重扫与之 diff → 删表/删列才反查受影响模板与指标并预警（首轮只建基准不告警）。
-- 租户隔离表（拦截器自动注入 tenant_id）；列名均小写归一后入 JSON。
CREATE TABLE IF NOT EXISTS schema_snapshot
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL COMMENT '所属组织（隔离键）',
    datasource_id BIGINT      NOT NULL COMMENT 'data_source.id',
    schema_json  MEDIUMTEXT   NOT NULL COMMENT '表→列集合序列化 JSON（小写归一，键=表名、值=列数组）',
    table_count  INT          NOT NULL DEFAULT 0 COMMENT '表数（摘要展示用，免解 JSON）',
    col_count    INT          NOT NULL DEFAULT 0 COMMENT '列数（摘要展示用）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次成功扫描时刻（diff 基准锚点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_schema_snapshot_ds (tenant_id, datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='结构变更感知快照（T12-3）';
