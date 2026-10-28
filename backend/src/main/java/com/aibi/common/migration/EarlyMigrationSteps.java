package com.aibi.common.migration;

import java.util.List;

/**
 * 迁移步骤第一段：V1~V15（配额/角色/分享 → 反馈闭环 → 历史快照 → 图表样式 → 多工作表/跨库
 * → 回放扩展 → RBAC 组织列 → 大屏背景/样式 → Skill 留痕 → 术语词典 → 操作日志 → 字段权限）。
 *
 * <p>自 {@code SchemaMigration.migrations()} 按原序整段平移，逻辑与注释零改动；
 * 助手调用改经 {@link MigrationContext}，直操 JDBC 改经 {@code ctx.jdbc()}。
 * 段内顺序即全局顺序前 15 位，禁止重排。
 */
public final class EarlyMigrationSteps {

    private EarlyMigrationSteps() {
    }

    public static List<MigrationStep> steps(MigrationContext ctx) {
        return List.of(

        // V1：租户配额 / 用户角色 / 大屏分享（历史增列，幂等）
        new MigrationStep("V2026_08_quota_role_share", () -> {
            ctx.addColumnIfMissing("tenant", "expire_at",
                    "expire_at DATETIME DEFAULT NULL COMMENT '使用期限（空=不过期）'");
            boolean addedDaily = ctx.addColumnIfMissing("tenant", "daily_limit",
                    "daily_limit INT NOT NULL DEFAULT 20 COMMENT '每日生成次数上限'");
            if (addedDaily) {
                // 首次增列时，按套餐为存量租户回填合理日限（FREE=20 / PRO=1000 / ENTERPRISE=不限）
                ctx.jdbc().update("UPDATE tenant SET daily_limit = CASE plan "
                        + "WHEN 'PRO' THEN 1000 WHEN 'ENTERPRISE' THEN -1 ELSE 20 END");
            }
            ctx.addColumnIfMissing("tenant", "total_limit",
                    "total_limit BIGINT NOT NULL DEFAULT -1 COMMENT '累计总次数上限（-1=不限）'");
            ctx.addColumnIfMissing("tenant", "total_used",
                    "total_used BIGINT NOT NULL DEFAULT 0 COMMENT '累计已用次数'");
            ctx.addColumnIfMissing("app_user", "role",
                    "role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT '角色: SUPER_ADMIN/USER'");
            ctx.addColumnIfMissing("dashboard", "share_token",
                    "share_token VARCHAR(64) DEFAULT NULL COMMENT '大屏公开分享令牌（空=未分享）'");
            ctx.addColumnIfMissing("dashboard", "share_password",
                    "share_password VARCHAR(64) DEFAULT NULL COMMENT '大屏分享访问密码（BCrypt 哈希，可空）'");
            ctx.addColumnIfMissing("dashboard", "share_expire_at",
                    "share_expire_at DATETIME DEFAULT NULL COMMENT '大屏分享过期时间（空=不过期）'");
        }),

        // V2：配置化架构升级——反馈闭环 / 图表意图留痕 / 指标与大屏配置扩展位
        new MigrationStep("V2026_09_configurable_feedback", () -> {
            ctx.addColumnIfMissing("query_history", "feedback",
                    "feedback TINYINT DEFAULT NULL COMMENT '用户反馈: 1=赞 -1=踩（空=未反馈）'");
            ctx.addColumnIfMissing("query_history", "feedback_at",
                    "feedback_at DATETIME DEFAULT NULL COMMENT '反馈时间'");
            ctx.addColumnIfMissing("query_history", "chart_requested",
                    "chart_requested VARCHAR(16) DEFAULT NULL COMMENT '问题中显式指定的图表类型'");
            ctx.addColumnIfMissing("query_history", "chart_used",
                    "chart_used VARCHAR(16) DEFAULT NULL COMMENT '最终推荐/使用的图表类型'");
            ctx.addColumnIfMissing("query_history", "error_msg",
                    "error_msg VARCHAR(500) DEFAULT NULL COMMENT '失败原因（status=FAILED 时）'");
            ctx.addColumnIfMissing("metric", "display_config",
                    "display_config VARCHAR(1024) DEFAULT NULL COMMENT '结果展示配置 JSON（默认图型/数值格式/行数上限覆盖）'");
            ctx.addColumnIfMissing("dashboard", "config",
                    "config MEDIUMTEXT COMMENT '大屏扩展配置 JSON（水印/边距/自定义变量等）'");
        }),

        // V3：问数历史结果快照 + 图表规格落库——历史会话回放据此还原"问数当时"的数据与图表；
        // 无快照的老记录由详情接口重跑已存 SQL 回放并回填
        new MigrationStep("V2026_09_history_snapshot", () -> {
            ctx.addColumnIfMissing("query_history", "result_json",
                    "result_json MEDIUMTEXT DEFAULT NULL COMMENT '结果快照 JSON（columns+前1000行），供历史回放'");
            ctx.addColumnIfMissing("query_history", "chart_json",
                    "chart_json VARCHAR(1024) DEFAULT NULL COMMENT '图表规格 JSON（type/xField/yFields/seriesField 等）'");
        }),

        // V4：图表展示样式——语义层沉淀度量单位（轴名/单位/数值格式随 chart_spec JSON 透传，无需专列），
        // 并放宽存 chart_spec 的三处列宽：样式字段加入后 JSON 变长，而 MySQL 严格模式下超长会直接报错——
        // share_link 只有 512 且读取方（ShareServiceImpl.readJson）解析失败会抛异常，等于整条分享链接打不开。
        new MigrationStep("V2026_09_chart_style", () -> {
            ctx.addColumnIfMissing("semantic_meta", "unit",
                    "unit VARCHAR(16) DEFAULT NULL COMMENT '度量单位（元/件/%/次），图表轴名与数值标注据此拼接'");
            ctx.widenColumn("share_link", "chart_spec",
                    "chart_spec VARCHAR(2048) DEFAULT NULL COMMENT '图表推荐 JSON'");
            ctx.widenColumn("metric", "chart_spec",
                    "chart_spec VARCHAR(2048) DEFAULT NULL COMMENT '图表规格 JSON'");
            ctx.widenColumn("query_history", "chart_json",
                    "chart_json VARCHAR(2048) DEFAULT NULL COMMENT '图表规格 JSON（type/xField/yFields/seriesField/style 等）'");
        }),

        // V5：图表标注默认值向已播种的老库补齐。
        // 必须仅对“sys_config 已有数据”的库执行：本类早于播种组运行，若在全新空库上先插入这几项，
        // SysConfigSeeder.seedSysConfig 会因“表非空”整体跳过，反而丢掉 NL2SQL/RESULT 等其余平台默认值。
        new MigrationStep("V2026_09_chart_style_config", () -> {
            Integer existing = ctx.jdbc().queryForObject("SELECT COUNT(*) FROM sys_config", Integer.class);
            if (existing == null || existing == 0) {
                return;   // 全新库：交由播种组（SysConfigSeeder）一次性播种全量
            }
            ctx.seedGlobalConfigIfAbsent("CHART", "value-format", "\"COMPACT\"",
                    "图表数值格式默认 COMPACT/THOUSAND/PLAIN");
            ctx.seedGlobalConfigIfAbsent("CHART", "legend-position", "\"bottom\"",
                    "图表图例默认位置 top/bottom/left/right/hidden");
            ctx.seedGlobalConfigIfAbsent("CHART", "value-decimals", "-1",
                    "图表数值默认小数位（-1=自动）");
            ctx.seedGlobalConfigIfAbsent("CHART", "auto-unit", "true",
                    "度量单位自动推断（语义层 unit → 列名关键词）");
        }),

        // V6：文件类数据源多工作表导入——存用户勾选的工作表名与"同构合并"开关。
        // 旧行为是只取第一个 sheet 建表名 data，故两列默认值必须等价于"全部且不合并"；
        // sheet_names 留空时 CsvFileAdapter 仍会建单表，单表名依旧为 data，已存指标的 SQL 不会失效。
        new MigrationStep("V2026_09_file_multi_sheet", () -> {
            ctx.addColumnIfMissing("data_source", "sheet_names",
                    "sheet_names VARCHAR(1024) DEFAULT NULL COMMENT '文件类勾选的工作表名 JSON 数组（空=全部）'");
            ctx.addColumnIfMissing("data_source", "sheet_merge",
                    "sheet_merge TINYINT NOT NULL DEFAULT 0 COMMENT '1=列结构完全一致的工作表合并为一张表'");
        }),

        // V7：跨库联合分析——除主库外再登记一批同服务器上的库/模式名，一并扫表进入提示词。
        // 默认 NULL 即“只扫主库”，与升级前行为完全一致；主库表名仍不带前缀，
        // 故已存指标的 SQL 与语义层（按表名键）均不受影响，只有额外库的表名才带“库.”前缀。
        new MigrationStep("V2026_09_multi_catalog", () ->
                ctx.addColumnIfMissing("data_source", "extra_catalogs",
                        "extra_catalogs VARCHAR(1024) DEFAULT NULL COMMENT '额外库/模式名 JSON 数组（空=只扫主库），其表名带库前缀以支持跨库 join'")),

        // V8：AI 数据解读与质量校验可回放——把问数当时产出的 insight/verify 随历史一并落库。
        // 二者此前只在实时响应里返回、从不落库，故点历史会话回放时看不到解读与校验结论。
        // 用 TEXT 而非 VARCHAR：中文解读/校验意见可能较长，严格模式下超长会直接报错、非严格模式静默截断，
        // 截断后回放反序列化即失败——TEXT(64KB) 足够容纳且无此风险。默认 NULL，与老记录同义（回放时不展示）。
        new MigrationStep("V2026_09_history_insight_verify", () -> {
            ctx.addColumnIfMissing("query_history", "insight_json",
                    "insight_json TEXT DEFAULT NULL COMMENT 'AI 数据解读 JSON（summary/keyPoints/anomalies），供历史回放还原'");
            ctx.addColumnIfMissing("query_history", "verify_json",
                    "verify_json TEXT DEFAULT NULL COMMENT 'AI 质量校验 JSON（answered/score/issues/suggestion），供历史回放还原'");
        }),

        // V9：RBAC 与组织协同地基——为存量表补齐席位/权限版本/团队归属/可见性等列。
        // 全部默认值均等价于"升级前行为"：visibility='TENANT'（组织内全可见）、seat_limit=-1（不限席位）、
        // team_id=NULL（组织级资源），保证升级后用户不会突然看不到自己原有的指标与驾驶舱。
        // 新表（14 张）由 metadata-schema.sql 的 CREATE TABLE IF NOT EXISTS 创建，不在此处重复。
        new MigrationStep("V2026_09_rbac_org", () -> {
            // ① tenant：席位、组织权限版本、默认团队、所有者与两个组织级开关
            ctx.addColumnIfMissing("tenant", "seat_limit",
                    "seat_limit INT NOT NULL DEFAULT -1 COMMENT '席位上限（-1=不限）'");
            ctx.addColumnIfMissing("tenant", "seat_used",
                    "seat_used INT NOT NULL DEFAULT 0 COMMENT '已用席位'");
            ctx.addColumnIfMissing("tenant", "perm_version",
                    "perm_version BIGINT NOT NULL DEFAULT 0 COMMENT '组织权限版本号，角色/权限/成员变更时 +1'");
            ctx.addColumnIfMissing("tenant", "default_team_id",
                    "default_team_id BIGINT DEFAULT NULL COMMENT '默认团队'");
            ctx.addColumnIfMissing("tenant", "owner_uid",
                    "owner_uid BIGINT DEFAULT NULL COMMENT '组织所有者'");
            ctx.addColumnIfMissing("tenant", "allow_viewer_query",
                    "allow_viewer_query TINYINT NOT NULL DEFAULT 1 COMMENT 'VIEWER 是否可问数'");
            ctx.addColumnIfMissing("tenant", "allow_self_invite",
                    "allow_self_invite TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许成员转发邀请链接自助入组'");
            // ② app_user：自然人身份关联、令牌版本（精确吊销）、当前团队与展示位
            ctx.addColumnIfMissing("app_user", "identity_id",
                    "identity_id BIGINT DEFAULT NULL COMMENT '自然人身份 user_identity.id'");
            ctx.addColumnIfMissing("app_user", "token_version",
                    "token_version BIGINT NOT NULL DEFAULT 0 COMMENT '令牌版本，停用/降级/重置口令时 +1'");
            ctx.addColumnIfMissing("app_user", "current_team_id",
                    "current_team_id BIGINT DEFAULT NULL COMMENT '上次选择的团队（登录默认值）'");
            ctx.addColumnIfMissing("app_user", "position",
                    "position VARCHAR(64) DEFAULT NULL COMMENT '组织内职位（展示用）'");
            ctx.addColumnIfMissing("app_user", "last_login_at",
                    "last_login_at DATETIME DEFAULT NULL COMMENT '最近登录'");
            // ③ 资源表：团队归属 + 可见性（默认 TENANT，等价于升级前的组织内全可见）
            for (String t : List.of("metric", "dashboard", "data_source", "query_favorite",
                    "few_shot_example", "semantic_meta")) {
                ctx.addColumnIfMissing(t, "team_id",
                        "team_id BIGINT DEFAULT NULL COMMENT '归属团队（NULL=组织级）'");
                ctx.addColumnIfMissing(t, "visibility",
                        "visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'PRIVATE/TEAM/TENANT'");
            }
            ctx.addColumnIfMissing("query_history", "team_id",
                    "team_id BIGINT DEFAULT NULL COMMENT '用量按团队归集'");
            ctx.addColumnIfMissing("share_link", "team_id",
                    "team_id BIGINT DEFAULT NULL COMMENT '用量按团队归集'");
            // ④ 索引：身份反查与团队维度列表查询是登录/切换团队的热路径
            ctx.createIndexIfMissing("app_user", "idx_identity", "(identity_id)");
            ctx.createIndexIfMissing("metric", "idx_team", "(tenant_id, team_id)");
            ctx.createIndexIfMissing("dashboard", "idx_team", "(tenant_id, team_id)");
        }),

        // V10：大屏自定义背景——每屏 background 覆盖主题 screenBg（空=跟随主题）。
        new MigrationStep("V2026_09_dashboard_background", () ->
                ctx.addColumnIfMissing("dashboard", "background",
                        "background VARCHAR(512) DEFAULT NULL COMMENT '大屏自定义背景（CSS background：色值/渐变/url()；空=跟随主题）'")),

        // V11：大屏级自定义样式——卡片/字体/配色覆盖（空=完全跟随主题）；逐组件样式存于 layout JSON 元素 style 字段。
        new MigrationStep("V2026_09_dashboard_style", () ->
                ctx.addColumnIfMissing("dashboard", "style_config",
                        "style_config MEDIUMTEXT COMMENT '大屏级自定义样式 JSON（卡片/字体/配色覆盖；空=完全跟随主题）'")),

        // V12：问数 Skill 化留痕——记录本次答问由哪个技能接手，用于统计各技能命中率与"是否走了大模型"；
        // 老记录为空（视为智能生成），不影响历史回放。
        new MigrationStep("V2026_09_ask_skill", () ->
                ctx.addColumnIfMissing("query_history", "skill",
                        "skill VARCHAR(32) DEFAULT NULL COMMENT '答问技能编码：direct_sql/metric/fewshot/nl2sql'")),

        // V13：业务术语词典——路由前把口语词（如"流水"）归一为规范名（如"营收"），
        // 提升指标直达/样例复用/数据字典问答等免 LLM 技能命中率；按租户+数据源维度维护，避免跨源串义。
        // CREATE TABLE IF NOT EXISTS 本身幂等，配合 applyIfAbsent 只执行一次。
        new MigrationStep("V2026_09_term_map", () ->
                ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS term_map ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "tenant_id BIGINT NOT NULL, "
                        + "datasource_id BIGINT NOT NULL, "
                        + "term VARCHAR(128) NOT NULL COMMENT '用户口语词', "
                        + "canonical VARCHAR(128) NOT NULL COMMENT '规范名', "
                        + "enabled TINYINT NOT NULL DEFAULT 1, "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (id), "
                        + "KEY idx_tenant_ds (tenant_id, datasource_id)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务术语词典'")),

        // V14：全局操作日志——请求级自动捕获（HandlerInterceptor 在 afterCompletion 落库），
        // 与事件驱动的 auth_audit_log 互补。跨租户表：查询范围由检索接口按身份显式收敛，
        // 故建表即 CREATE TABLE IF NOT EXISTS（配合 applyIfAbsent 只执行一次）。
        new MigrationStep("V2026_09_operation_log", () ->
                ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS audit_log ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '归属租户（0=平台域）；仅作标记，不作隔离', "
                        + "user_id BIGINT DEFAULT NULL COMMENT '操作人 uid（匿名请求为空）', "
                        + "username VARCHAR(64) DEFAULT NULL COMMENT '操作人登录名', "
                        + "org_roles VARCHAR(255) DEFAULT NULL COMMENT '组织角色码（逗号分隔）', "
                        + "platform_roles VARCHAR(255) DEFAULT NULL COMMENT '平台角色码（逗号分隔，仅平台域非空）', "
                        + "request_id VARCHAR(64) DEFAULT NULL COMMENT '请求追踪 id', "
                        + "module VARCHAR(32) DEFAULT NULL COMMENT '业务模块（org:metrics/chat/admin:audit...）', "
                        + "action VARCHAR(96) DEFAULT NULL COMMENT '可读动作：METHOD /path', "
                        + "method VARCHAR(8) DEFAULT NULL COMMENT 'HTTP 方法', "
                        + "uri VARCHAR(512) DEFAULT NULL COMMENT '请求路径（不含查询串）', "
                        + "query_string VARCHAR(1024) DEFAULT NULL COMMENT '原始查询串', "
                        + "request_body MEDIUMTEXT DEFAULT NULL COMMENT '请求体（脱敏+截断，禁存口令）', "
                        + "ip VARCHAR(64) DEFAULT NULL COMMENT '客户端 IP（X-Forwarded-For 首段）', "
                        + "user_agent VARCHAR(512) DEFAULT NULL COMMENT 'User-Agent', "
                        + "http_status INT DEFAULT NULL COMMENT 'HTTP 状态码', "
                        + "error_msg VARCHAR(512) DEFAULT NULL COMMENT '异常信息（仅失败时）', "
                        + "duration_ms BIGINT DEFAULT NULL COMMENT '处理耗时（毫秒）', "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时刻', "
                        + "PRIMARY KEY (id), "
                        + "KEY idx_tenant_time (tenant_id, created_at), "
                        + "KEY idx_user_time (user_id, created_at), "
                        + "KEY idx_module_time (module, created_at)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='全局操作日志'")),

        // V15：语义层字段级权限——敏感列标记 + 可见角色声明（结果层按当前角色脱敏，见 FieldPermissionService）。
        // 与前端语义层编辑页、问数权限域护栏（QuestionGuard.checkPermission）配套；老记录均为 NULL（普通列），
        // 升级后行为不变。两列此前漏入迁移，导致 SemanticService.list 的 SELECT 报
        // Unknown column 'sensitive'（sensitive 排在 visible_roles 前，先报错；只补一列会立即转移到另一列）。
        new MigrationStep("V2026_09_semantic_sensitive", () -> {
            // 注意：sensitive 是 MySQL 保留字，columnDdl 必须反引号转义，否则 ALTER 同样报 1064。
            ctx.addColumnIfMissing("semantic_meta", "sensitive",
                    "`sensitive` TINYINT DEFAULT NULL COMMENT '是否敏感列：1=需角色可见；空/null=普通（无字段级脱敏）'");
            ctx.addColumnIfMissing("semantic_meta", "visible_roles",
                    "`visible_roles` VARCHAR(255) DEFAULT NULL COMMENT '允许查看的角色码（逗号分隔，如 OWNER,ADMIN,DATA_STEWARD）；空=仅 OWNER/ADMIN 可见'");
        })
        );
    }
}
