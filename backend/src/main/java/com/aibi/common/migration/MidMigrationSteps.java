package com.aibi.common.migration;

import java.util.List;

/**
 * 迁移步骤第二段：V16~V30（指标别名/血缘/口径/归因默认 → 预警归因与通知目标 → 新鲜度
 * → 配置管控/资源分组 → 归因落库 → 数据权限 A(v1) 四表与表维度联动 → 语义/指标来源标记
 * → 嵌入问数与多选数据源 → LLM 请求体扩展位）。
 *
 * <p>自 {@code SchemaMigration.migrations()} 按原序整段平移，逻辑与注释零改动。
 * 段内顺序即全局顺序第 16~33 位，禁止重排（V26b 依赖 V26 先建四张 ACL 表）。
 */
public final class MidMigrationSteps {

    private MidMigrationSteps() {
    }

    public static List<MigrationStep> steps(MigrationContext ctx) {
        return List.of(

        // V16：指标展示别名——与库内 name 分离，作为图表标题/大屏组件默认别名；空则回落 name。
        // 老记录均为 NULL（等同用 name），升级后行为不变。
        new MigrationStep("V2026_09_metric_alias", () ->
                ctx.addColumnIfMissing("metric", "alias",
                        "alias VARCHAR(128) DEFAULT NULL COMMENT '指标展示别名（图表标题/大屏组件默认名；空=回落 name）'")),

        // V17：指标 canonical 口径表达式——趋势/量价因子/维度归因三处共用同一口径，避免口径漂移。
        // 老记录均为 NULL（等同未沉淀口径），升级后不影响既有保存链路。
        // V18：数据血缘边表（需求 §3.3）。CREATE IF NOT EXISTS 幂等；表内 to_id 可空（表无实体 id）。
        new MigrationStep("V2026_09_lineage_edge", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS lineage_edge ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "tenant_id BIGINT NOT NULL,"
                        + "from_type VARCHAR(24) NOT NULL,"
                        + "from_id BIGINT NULL,"
                        + "from_name VARCHAR(128) NULL,"
                        + "to_type VARCHAR(24) NOT NULL,"
                        + "to_id BIGINT NULL,"
                        + "to_name VARCHAR(128) NULL,"
                        + "relation VARCHAR(32) NOT NULL,"
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "UNIQUE KEY uk_lineage (tenant_id, from_type, from_id, to_type, to_id, relation),"
                        + "KEY idx_lineage_to (tenant_id, to_type, to_id)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 "
                        + "COMMENT = '数据血缘边'")),

        new MigrationStep("V2026_09_metric_definition", () ->
                ctx.addColumnIfMissing("metric", "definition",
                        "definition VARCHAR(512) DEFAULT NULL COMMENT 'canonical 指标口径表达式（如 SUM(amount)）；趋势/因子/归因共用，空=未沉淀'")),

        // V19：指标归因默认值（P1）——保存指标时一并沉淀归因维度与对比基准，下次打开自动执行归因。
        new MigrationStep("V2026_09_metric_attribution_default", () -> {
            ctx.addColumnIfMissing("metric", "attribution_dimension",
                    "attribution_dimension VARCHAR(64) DEFAULT NULL COMMENT '归因默认维度（物理列名）；空=由用户选择'");
            ctx.addColumnIfMissing("metric", "baseline_type",
                    "baseline_type VARCHAR(16) DEFAULT NULL COMMENT '归因默认对比基准 MOM/YOY/PEAK；空=回落 MOM'");
        }),

        // V20：预警触发预跑归因（P1）——规则开「自动归因」后，触发时自动跑一次单维归因并随记录落库，
        // 让管理员收到告警的同时就知道"是谁拖累的"。默认 0 即升级前行为（只报数、不归因），老规则不受影响。
        // 注意：alert_rule/alert_record 两表的建表已并入 metadata-schema.sql（原 backend/sql/ 手工脚本，2026-10-24 挂账闭环）。
        new MigrationStep("V2026_09_alert_attribution", () -> {
            ctx.addColumnIfMissing("alert_rule", "auto_attribution",
                    "auto_attribution TINYINT NOT NULL DEFAULT 0 COMMENT '1=触发时自动预跑归因（仅业务指标 metric:<id> 生效）'");
            ctx.addColumnIfMissing("alert_rule", "attribution_dimension",
                    "attribution_dimension VARCHAR(64) DEFAULT NULL COMMENT '归因维度（物理列名）；空=用指标的归因默认维度'");
            ctx.addColumnIfMissing("alert_record", "attribution",
                    "attribution TEXT DEFAULT NULL COMMENT '触发时预跑的归因摘要 JSON（AlertAttributionVO）；无归因时为 NULL'");
        }),

        // V24b：预警通知目标（P2 通知链路闭环）——规则除「用哪个渠道（notify_channel）」外，
        // 还需要「发给谁（notify_target）」：EMAIL=收件人邮箱（逗号/分号分隔），
        // 预留 WEBHOOK=回调 URL、SMS=手机号。默认 NULL，老规则行为不变（仍是站内日志）。
        new MigrationStep("V2026_09_alert_notify_target", () -> {
            ctx.addColumnIfMissing("alert_rule", "notify_target",
                    "notify_target VARCHAR(512) DEFAULT NULL COMMENT '通知目标：EMAIL=收件人（逗号分隔），WEBHOOK=URL，SMS=手机号'");
        }),

        // V21：数据新鲜度（P2）——指标数据最后成功刷新时间 + 指标级 SLA 覆盖。
        // 关键：不能用 updated_at 代替 refreshed_at —— 后者也会因改别名/改口径等元数据编辑而刷新，
        // 会让"数据明明是三天前拉的"却显示"刚刚更新"，正是新鲜度提示要防的误导。
        // 老行 refreshed_at 为 NULL：语义是"从未刷新过"，与"已过期"区分开由前端按 never 状态呈现（标黄且文案不同）。
        new MigrationStep("V2026_09_metric_freshness", () -> {
            ctx.addColumnIfMissing("metric", "refreshed_at",
                    "refreshed_at DATETIME DEFAULT NULL COMMENT '数据最后成功刷新时间（区别于 updated_at 的元数据变更）'");
            ctx.addColumnIfMissing("metric", "freshness_hours",
                    "freshness_hours INT DEFAULT NULL COMMENT '该指标的新鲜度 SLA（小时）；空=回落全局 FRESHNESS/sla-hours'");
        }),

        // V22：数据新鲜度全局 SLA 默认值（P2）。
        // 与 V5 同因：SysConfigSeeder 首批只在 sys_config 空表时整体播种，运行库升级永远拿不到新配置项，
        // 故这里对"已有数据的老库"单独补种；全新库留给播种组，避免两处重复插入。
        new MigrationStep("V2026_09_freshness_config", () -> {
            Integer existing = ctx.jdbc().queryForObject("SELECT COUNT(*) FROM sys_config", Integer.class);
            if (existing == null || existing == 0) {
                return;   // 全新库：交由播种组（SysConfigSeeder）一次性播种全量
            }
            ctx.seedGlobalConfigIfAbsent("FRESHNESS", "sla-hours", "24",
                    "数据新鲜度 SLA 阈值（小时）：指标数据超过该时长未刷新即标黄；指标可单独覆盖");
        }),

        // V23：配置管控 —— sys_config 增「平台管控」标记，使超管可把任意配置键锁死为平台值
        // （忽略并拒绝租户覆盖）。存量行为不变：新列默认 0，即"平台默认 + 租户可覆盖"。
        new MigrationStep("V2026_09_config_managed", () -> ctx.addColumnIfMissing("sys_config", "managed",
                "managed TINYINT NOT NULL DEFAULT 0 COMMENT '平台管控：1=GLOBAL 行锁死为平台值，忽略并拒绝 TENANT 覆盖'")),

        // V24：资源分组——驾驶舱与指标库**共用**一张目录树 resource_group（parent_id 成树，
        // resource_type 分域：DASHBOARD / METRIC 两棵树彼此独立），并给 dashboard / metric 各加 group_id 归属
        // （空=未分组）。分组删空前禁止删除，故 group_id 不会悬挂。建表 CREATE IF NOT EXISTS 幂等。
        // 注：本表此前计划叫 dashboard_group，因该迁移从未在任何库执行过而得以直接改为通用命名，无数据迁移负担。
        new MigrationStep("V2026_09_resource_group", () -> {
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS resource_group ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "tenant_id BIGINT NOT NULL,"
                    + "uid BIGINT NOT NULL COMMENT '创建者',"
                    + "resource_type VARCHAR(32) NOT NULL COMMENT '分组域：DASHBOARD / METRIC（两棵树彼此独立）',"
                    + "name VARCHAR(64) NOT NULL,"
                    + "parent_id BIGINT DEFAULT NULL COMMENT '父分组 id（空=根级）',"
                    + "sort INT NOT NULL DEFAULT 0 COMMENT '同级展示顺序',"
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id), KEY idx_tenant_type_parent (tenant_id, resource_type, parent_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资源分组目录树（驾驶舱/指标库共用）'");
            ctx.addColumnIfMissing("dashboard", "group_id",
                    "group_id BIGINT DEFAULT NULL COMMENT '所属分组（resource_group.id，空=未分组）'");
            ctx.addColumnIfMissing("metric", "group_id",
                    "group_id BIGINT DEFAULT NULL COMMENT '所属分组（resource_group.id，空=未分组）'");
        }),

        // V25：问数历史的「维度波动归因」产物持久化 —— query_history 增 attribution_json。
        // 背景：数据解读(insight_json) 与 质量校验(verify_json) 早已落库、回放可还原，唯独归因此前只算不存，
        // 于是历史回放时归因面板空白（用户反馈）。归因结果在 run 时按请求携带的 historyId 回写对应历史行。
        // 老记录为 NULL：回放时不显示归因，且**不重算**——归因依赖当时的口径/维度选择，重跑既耗资源也可能与当时不一致。
        new MigrationStep("V2026_09_history_attribution", () -> ctx.addColumnIfMissing("query_history", "attribution_json",
                "attribution_json MEDIUMTEXT DEFAULT NULL COMMENT '维度波动归因结果 JSON（AttributionVO）；历史回放据此还原归因面板'")),

        // V26：数据权限 A（v1）——成员直授 ∪ 角色继承 的数据源可见性 + 字段脱敏（见《组织治理专项设计》§3）。
        // 四张表均 CREATE TABLE IF NOT EXISTS（配合 applyIfAbsent 只执行一次），老库启动即补齐；
        // 与 metadata-schema.sql 的建表语句保持同构（双引号标识符 + InnoDB + utf8mb4）。
        new MigrationStep("V2026_09_data_acl", () -> {
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS user_datasource_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "uid BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'HIDDEN/ALLOW', "
                    + "granted_by BIGINT DEFAULT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_user_ds (tenant_id, uid, datasource_id), "
                    + "KEY idx_tenant_ds (tenant_id, datasource_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='成员数据源可见性'");
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS user_field_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "uid BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "column_name VARCHAR(128) NOT NULL, "
                    + "table_name VARCHAR(128) DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列', "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'MASK/UNMASK', "
                    + "granted_by BIGINT DEFAULT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_user_col (tenant_id, uid, datasource_id, table_name, column_name), "
                    + "KEY idx_tenant_ds (tenant_id, datasource_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='成员字段脱敏'");
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS role_datasource_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "custom_role_id BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'HIDDEN/ALLOW', "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_role_ds (tenant_id, custom_role_id, datasource_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色数据源可见性'");
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS role_field_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "custom_role_id BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "column_name VARCHAR(128) NOT NULL, "
                    + "table_name VARCHAR(128) DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列', "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'MASK/UNMASK', "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_role_col (tenant_id, custom_role_id, datasource_id, table_name, column_name)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色字段脱敏'");
        }),

        // V26b：字段脱敏「表维度」联动 —— 在 V26 的四张表之上，给 user_field_acl / role_field_acl 补
        // table_name 列并把唯一键扩为含 table_name（NULL=全局同名列，非 NULL=特定表.列）。
        // 老库里 V26 已建表（无 table_name），故此处幂等补齐：addColumnIfMissing 跳过已存在列；
        // ensureFieldAclTableScope 仅当 uk 尚未含 table_name 时才 DROP+ADD。全新库走 V26 已带 table_name，
        // 此处两道幂等守卫直接跳过，不会重复动手。
        new MigrationStep("V2026_09_data_acl_table_scope", () -> {
            ctx.addColumnIfMissing("user_field_acl", "table_name",
                    "table_name VARCHAR(128) DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列'");
            ctx.addColumnIfMissing("role_field_acl", "table_name",
                    "table_name VARCHAR(128) DEFAULT NULL COMMENT '表名（库前缀可选）；NULL=作用于该数据源所有同名列'");
            ctx.ensureFieldAclTableScope("user_field_acl", "uk_user_col",
                    "tenant_id, uid, datasource_id, table_name, column_name");
            ctx.ensureFieldAclTableScope("role_field_acl", "uk_role_col",
                    "tenant_id, custom_role_id, datasource_id, table_name, column_name");
        }),

        // V27：指标后台自动分析 Skill + 驾驶舱 AI 自动化大屏 —— 语义层增「来源」标记（MANUAL/SKELETON/AI）。
        // AI 自动分析 Skill 扫描库表后写入的行打 source='AI'（前端据此展示 AI 自动识别标识）；
        // 骨架自动初始化行打 source='SKELETON'；人工维护行 source 为 NULL/空（等同 MANUAL）。默认 NULL，
        // 与升级前行为一致（旧行视为人工维护），升级后已有语义层不突然多出 AI 标识。
        new MigrationStep("V2026_09_semantic_source", () ->
                ctx.addColumnIfMissing("semantic_meta", "source",
                        "source VARCHAR(16) DEFAULT NULL COMMENT '来源标记：MANUAL/SKELETON/AI（AI 自动分析识别）；空=人工维护'")),

        // V28：指标来源标记——指标后台自动分析 Skill 生成的指标打 source='AI'（前端据此展示 AI 自动识别标识）。
        // 老记录均为 NULL（等同人工维护），升级后不会突然多出 AI 标识；人工编辑指标不覆盖已有值。
        new MigrationStep("V2026_09_metric_source", () ->
                ctx.addColumnIfMissing("metric", "source",
                        "source VARCHAR(16) DEFAULT NULL COMMENT '来源标记：AI=指标后台自动分析生成；空=人工维护'")),

        // V29：对话问数嵌入第三方页面 —— chat_embed（匿名令牌 → 数据源 的实时问数嵌入）。
        // 第三方页面以 iframe 嵌入 /embed/chat/{token}，凭令牌匿名提问；令牌即唯一能力凭证，
        // 只允许"对该数据源提问"这一件事。allowed_origins 留空=不限制嵌入站点，否则按前缀白名单校验。
        new MigrationStep("V2026_09_chat_embed", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS chat_embed ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " tenant_id BIGINT NOT NULL,"
                        + " uid BIGINT DEFAULT NULL,"
                        + " name VARCHAR(128) DEFAULT NULL,"
                        + " token VARCHAR(64) NOT NULL,"
                        + " datasource_id BIGINT NOT NULL,"
                        + " datasource_ids VARCHAR(1024) DEFAULT NULL,"
                        + " enabled TINYINT NOT NULL DEFAULT 1,"
                        + " expire_at DATETIME DEFAULT NULL,"
                        + " allowed_origins VARCHAR(512) DEFAULT NULL,"
                        + " view_count INT NOT NULL DEFAULT 0,"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + " UNIQUE KEY uk_chat_embed_token (token),"
                        + " KEY idx_chat_embed_tenant (tenant_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                        + "COMMENT='对话问数嵌入：第三方页面匿名嵌入令牌'")),

        // V29b：数据源支持多选 —— 旧部署的 chat_embed 仅 datasource_id 单列，需补 datasource_ids 并回填。
        new MigrationStep("V2026_09_chat_embed_multids", () -> {
            ctx.addColumnIfMissing("chat_embed", "datasource_ids",
                    "datasource_ids VARCHAR(1024) DEFAULT NULL COMMENT '被开放的数据源 id 列表，逗号分隔（多选）'");
            // 兼容旧单行数据：把已存的 datasource_id 回填进 datasource_ids
            ctx.jdbc().update("UPDATE chat_embed SET datasource_ids = CAST(datasource_id AS CHAR) "
                    + "WHERE datasource_ids IS NULL OR datasource_ids = ''");
        }),

        // V30：大模型请求体扩展位 —— llm_config 增 extra_body（JSON，逐字段并入请求体顶层）与 max_tokens。
        // 用途：对支持该协议的网关关闭"思考模式/推理"（extra_body={"thinking":{"type":"disabled"}}）并限制输出长度。
        // 推理模型的思考 token 既显著拉长单次调用耗时，又可能挤占 max_tokens 导致答案被截断，是"问数非常慢"主因之一。
        // 两列默认 NULL = 请求体完全不变，老配置行为不回归。
        new MigrationStep("V2026_09_llm_extra_body", () -> {
            ctx.addColumnIfMissing("llm_config", "extra_body",
                    "extra_body VARCHAR(1000) DEFAULT NULL COMMENT '额外请求体 JSON（逐字段并入顶层），如 {\"thinking\":{\"type\":\"disabled\"}}'");
            ctx.addColumnIfMissing("llm_config", "max_tokens",
                    "max_tokens INT DEFAULT NULL COMMENT '输出 token 上限；空=用供应商默认'");
        })
        );
    }
}
