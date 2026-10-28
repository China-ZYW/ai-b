package com.aibi.common.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 迁移步骤第三段：V31~V44（站内信 → 嵌入对话日志 → MCP PAT → 数据源配额 → 注册防刷
 * → 表关联登记 → 表结构同步时间+表级 ACL(v2) → 枚举字典 → SQL 编辑器模板 → 构造器规格 → 账号级主题 → 待治理问题台账 → 反馈修正采集 → 定时报表）。
 *
 * <p>自 {@code SchemaMigration.migrations()} 按原序整段平移，逻辑与注释零改动。
 * 段内顺序即全局顺序第 34~47 位，禁止重排。
 */
public final class LateMigrationSteps {

    private static final Logger log = LoggerFactory.getLogger(LateMigrationSteps.class);

    private LateMigrationSteps() {
    }

    public static List<MigrationStep> steps(MigrationContext ctx) {
        return List.of(

        // V31：站内信——预警命中时按规则 notify_target 指定的用户 id 写库（每人一条），作为第 8 个告警渠道 INAPP。
        // CREATE TABLE IF NOT EXISTS 幂等；配合 applyIfAbsent 只执行一次（已有库启动即补齐，全新库由 metadata-schema.sql 建）。
        new MigrationStep("V2026_09_notify_message", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS notify_message ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " tenant_id BIGINT NOT NULL,"
                        + " uid BIGINT NOT NULL COMMENT '接收人 app_user.id',"
                        + " rule_id BIGINT DEFAULT NULL COMMENT '来源预警规则 id',"
                        + " record_id BIGINT DEFAULT NULL COMMENT '来源预警触发记录 id',"
                        + " severity VARCHAR(16) DEFAULT NULL COMMENT 'INFO/WARN/CRITICAL',"
                        + " title VARCHAR(255) DEFAULT NULL COMMENT '标题',"
                        + " content TEXT DEFAULT NULL COMMENT '正文',"
                        + " is_read TINYINT NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读',"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " KEY idx_notify_message_tenant_uid (tenant_id, uid),"
                        + " KEY idx_notify_message_tenant_unread (tenant_id, uid, is_read)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='站内信'")),

        // V32：嵌入问数·外部用户对话日志——匿名 iframe 嵌入时按嵌入方传入的 user 唯一值归并每次问数，
        // 同时承担审计留痕（detail 记 ext_user/origin）与初始化历史回看（右侧悬浮面板）两职责。
        // CREATE TABLE IF NOT EXISTS 幂等；配合 applyIfAbsent 只执行一次（已有库启动即补齐，全新库由 metadata-schema.sql 建）。
        new MigrationStep("V2026_09_chat_embed_log", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS chat_embed_log ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " tenant_id BIGINT NOT NULL,"
                        + " embed_id BIGINT NOT NULL COMMENT 'chat_embed.id',"
                        + " token VARCHAR(64) NOT NULL COMMENT 'chat_embed.token',"
                        + " ext_user VARCHAR(128) DEFAULT NULL COMMENT '嵌入方传入的外部用户唯一值(?user=)',"
                        + " conversation_id VARCHAR(64) DEFAULT NULL COMMENT '多轮会话 id',"
                        + " role VARCHAR(16) NOT NULL COMMENT 'USER/BOT',"
                        + " content TEXT DEFAULT NULL COMMENT '消息内容(用户问句/机器人结论摘要)',"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " KEY idx_chat_embed_log_scope (tenant_id, embed_id, ext_user),"
                        + " KEY idx_chat_embed_log_created (tenant_id, embed_id, ext_user, created_at)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='嵌入问数-外部用户对话日志'")),

        // V33：对外 MCP 的个人访问令牌（PAT）——外部 AI 客户端凭它调用 POST /mcp。
        // 只存 SHA-256 哈希 + 展示前缀，明文不落库（创建时返回一次）；revoked_at 非空即失效，可即时吊销。
        new MigrationStep("V2026_09_mcp_pat", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS mcp_pat ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）',"
                        + " uid BIGINT NOT NULL COMMENT '令牌绑定用户 app_user.id',"
                        + " name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '令牌名称',"
                        + " token_prefix VARCHAR(32) NOT NULL DEFAULT '' COMMENT '明文前缀，仅展示',"
                        + " token_hash VARCHAR(128) NOT NULL COMMENT '明文 SHA-256 十六进制',"
                        + " scopes VARCHAR(128) DEFAULT NULL COMMENT '授权范围，逗号分隔',"
                        + " expires_at DATETIME DEFAULT NULL COMMENT '过期时间，空=不过期',"
                        + " revoked_at DATETIME DEFAULT NULL COMMENT '非空=已吊销',"
                        + " last_used_at DATETIME DEFAULT NULL COMMENT '最近一次调用时间',"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " UNIQUE KEY uk_mcp_pat_hash (token_hash),"
                        + " KEY idx_mcp_pat_tenant (tenant_id, id),"
                        + " KEY idx_mcp_pat_uid (tenant_id, uid)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对外MCP个人访问令牌'")),

        // V34：数据源数量配额上限（定价分析#4 高优先）——根除"无限数据源"超卖。
        // 存量库按套餐回填 FREE=1 / PRO=5 / ENTERPRISE=-1（首次增列时）；全新库默认 -1，由注册流程按 plan 赋初值。
        new MigrationStep("V2026_09_datasource_limit", () -> {
            boolean added = ctx.addColumnIfMissing("tenant", "datasource_limit",
                    "datasource_limit INT NOT NULL DEFAULT -1 COMMENT '数据源数量上限（-1=不限）'");
            if (added) {
                ctx.jdbc().update("UPDATE tenant SET datasource_limit = CASE plan "
                        + "WHEN 'PRO' THEN 5 WHEN 'ENTERPRISE' THEN -1 ELSE 1 END");
            }
        }),

        // V35：注册防刷（体验版无限注册治理）——邮箱全局唯一 + 每 IP 仅可自助开通一个免费工作区。
        new MigrationStep("V2026_09_register_guard", () -> {
            // ① tenant 记录自助注册来源 IP（邀请注册不写，避免同办公室多人被误伤）
            ctx.addColumnIfMissing("tenant", "register_ip",
                    "register_ip VARCHAR(64) DEFAULT NULL COMMENT '自助注册来源IP（每IP仅可开通一个免费工作区）'");
            // ② app_user.email 唯一索引：dup-safe——存在非空重复邮箱时跳过，改由注册逻辑应用层强校验，避免启动失败
            Long dup = ctx.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM (SELECT email FROM app_user WHERE email IS NOT NULL "
                            + "GROUP BY email HAVING COUNT(*) > 1) d", Long.class);
            if (dup == null || dup == 0) {
                ctx.createIndexIfMissing("app_user", "uk_app_user_email", "(email)");
            } else {
                log.warn("app_user 存在 {} 组重复邮箱，跳过 uk_app_user_email 索引；已由注册逻辑应用层强校验兜底", dup);
            }
        }),

        // V36：表关联登记（多表问数准确率）——语义层补上第三级“关系语义”。
        // 业务库与分析型库普遍不建 FOREIGN KEY，此前只能靠命名推断（可能推错）；
        // 人工登记的连接键以最高可信度并入 JoinGraph，且能声明推不出来的多对多。
        // CREATE TABLE IF NOT EXISTS 幂等；配合 applyIfAbsent 只执行一次（全新库由 metadata-schema.sql 建）。
        new MigrationStep("V2026_09_table_relation", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS table_relation ("
                        + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）',"
                        + " datasource_id BIGINT NOT NULL COMMENT 'data_source.id',"
                        + " left_table VARCHAR(128) NOT NULL COMMENT '多方表名（持有连接键的一侧）',"
                        + " left_column VARCHAR(128) NOT NULL COMMENT '多方连接列',"
                        + " right_table VARCHAR(128) NOT NULL COMMENT '一方表名',"
                        + " right_column VARCHAR(128) NOT NULL COMMENT '一方连接列',"
                        + " relation_type VARCHAR(16) NOT NULL DEFAULT 'MANY_TO_ONE'"
                        + " COMMENT 'MANY_TO_ONE/ONE_TO_ONE/MANY_TO_MANY',"
                        + " description VARCHAR(500) DEFAULT NULL COMMENT '业务口径说明（为何这样关联）',"
                        + " enabled TINYINT NOT NULL DEFAULT 1,"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + " KEY idx_tr_tenant_ds (tenant_id, datasource_id),"
                        + " UNIQUE KEY uk_tr_pair (tenant_id, datasource_id, left_table, left_column,"
                        + " right_table, right_column)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='表关联登记（人工维护的连接键口径）'")),

        // V37：数据源表结构同步时间 + 表级数据授权（v2）——修复"数据源新增表系统没同步"与"数据授权无表维度"。
        // ① data_source.last_schema_sync_at：手动同步按钮 / 定时同步调度器刷新缓存后落盘，前端据此提示结构可能过期；
        //    老记录均为 NULL（语义=从未同步），升级后行为不变。
        // ② user_table_acl / role_table_acl：表级可见性（HIDDEN/ALLOW），与数据源可见性同模型（黑名单：无行=可见），
        //    在 schema 层省略被隐藏表（LLM 看不到）+ SQL 执行前拦截（防手填 SQL 绕过）；二者 CREATE IF NOT EXISTS 幂等。
        new MigrationStep("V2026_09_table_acl_sync", () -> {
            ctx.addColumnIfMissing("data_source", "last_schema_sync_at",
                    "last_schema_sync_at DATETIME DEFAULT NULL COMMENT '最近一次表结构同步时间（手动/定时同步刷新缓存后写入）'");
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS user_table_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "uid BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "table_name VARCHAR(128) NOT NULL COMMENT '表名（库前缀可选）', "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'HIDDEN/ALLOW', "
                    + "granted_by BIGINT DEFAULT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_user_tbl (tenant_id, uid, datasource_id, table_name), "
                    + "KEY idx_tenant_ds (tenant_id, datasource_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='成员表级可见性'");
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS role_table_acl ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "custom_role_id BIGINT NOT NULL, "
                    + "datasource_id BIGINT NOT NULL, "
                    + "table_name VARCHAR(128) NOT NULL COMMENT '表名（库前缀可选）', "
                    + "effect VARCHAR(8) NOT NULL COMMENT 'HIDDEN/ALLOW', "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_role_tbl (tenant_id, custom_role_id, datasource_id, table_name)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色表级可见性'");
        }),

        // V38：语义层枚举字典——采样对 status/type/level 这类数字编码列只能拿到 ["1","2","3","4"]，
        // 注入 Prompt 后又只剩一句裸取值列表，模型不知道 1 是什么，结果层也照样显示 1234，采样等于空转。
        // 新增 enum_labels 存「码值 → 中文标签」映射（JSON 对象，如 {"1":"已支付","2":"已取消"}），
        // Prompt 输出成「枚举:1,2,3,4；字典:1=已支付，2=已取消」，模型即可理解码值含义。
        // 老记录均为 NULL（未配字典），升级后 Prompt 行为与升级前完全一致。
        new MigrationStep("V2026_09_enum_labels", () ->
                ctx.addColumnIfMissing("semantic_meta", "enum_labels",
                        "enum_labels VARCHAR(2000) DEFAULT NULL COMMENT '枚举字典 JSON 对象（码值→中文标签，如 {\"1\":\"已支付\"}）'")),

        // V39：SQL 编辑器（高级查询 P1）——保存的查询模板 + 问数历史埋点三列。
        // ① saved_query：存 :param 命名占位符模板，运行期走 PreparedStatement 绑定（绝不拼接 SQL，杜绝注入）；
        //    与 query_history 分工明确——history 记"跑过什么"，saved_query 记"留下来复用什么"。
        // ② query_history 补三列埋点：skill_code（各答问技能命中率）/ chart_type（各图型使用率）/
        //    template_id（区分临时 SQL 与跑模板），为 P3 埋点分析提供数据来源。
        //    老记录三列均为 NULL，升级后行为与升级前完全一致。
        new MigrationStep("V2026_09_saved_query", () -> {
            ctx.jdbc().execute("CREATE TABLE IF NOT EXISTS saved_query ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）', "
                    + "uid BIGINT NOT NULL COMMENT '创建人 app_user.id', "
                    + "datasource_id BIGINT NOT NULL COMMENT 'data_source.id', "
                    + "name VARCHAR(64) NOT NULL COMMENT '模板名称（同租户同人下唯一）', "
                    + "sql_template TEXT NOT NULL COMMENT '带 :param 命名占位符的 SQL 模板', "
                    + "param_names VARCHAR(255) NULL COMMENT '参数名，逗号分隔（与 :param 出现顺序一致）', "
                    + "description VARCHAR(200) NULL COMMENT '模板说明', "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_saved_query_name (tenant_id, uid, name), "
                    + "KEY idx_saved_query_ds (tenant_id, datasource_id)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 "
                    + "COMMENT ='SQL 查询模板（高级查询保存的查询）'");
            ctx.addColumnIfMissing("query_history", "skill_code",
                    "skill_code VARCHAR(32) DEFAULT NULL COMMENT '命中的答问技能编码'");
            ctx.addColumnIfMissing("query_history", "chart_type",
                    "chart_type VARCHAR(24) DEFAULT NULL COMMENT '本次结果采用的图型'");
            ctx.addColumnIfMissing("query_history", "template_id",
                    "template_id BIGINT DEFAULT NULL COMMENT '来源查询模板 id（为空=临时 SQL）'");
        }),

        // V40：可视化构造器规格持久化（高级查询 P2）——让"在构造器里搭的查询"能原样存为模板/历史，
        // 下次加载时还原回构造器（而非退化为纯 SQL）。saved_query.spec / query_history.spec 存 BuilderSpec JSON。
        new MigrationStep("V2026_09_saved_query_spec", () -> {
            ctx.addColumnIfMissing("saved_query", "spec",
                    "spec TEXT NULL COMMENT '构造器规格 JSON（BuilderSpec；非空=来自可视化构造器）'");
            ctx.addColumnIfMissing("query_history", "spec",
                    "spec TEXT NULL COMMENT '构造器规格 JSON（来自可视化构造器运行时才有）'");
        }),

        // V41：账号级 UI 明暗主题偏好（系统主题切换）——app_user.ui_theme 存 light/dark/system 三态，
        // 默认 'system'（跟随操作系统），使"升级前无该列"的存量账号行为与升级后默认完全一致。
        // 之所以落在 app_user 而不是 sys_config：配置中心只有 GLOBAL/TENANT 两级 scope，
        // 没有 USER 级；主题是"人"的属性而非"组织"的属性，换组织不该丢主题。
        new MigrationStep("V2026_09_ui_theme", () ->
                ctx.addColumnIfMissing("app_user", "ui_theme",
                        "ui_theme VARCHAR(16) NOT NULL DEFAULT 'system' "
                                + "COMMENT '账号级界面主题偏好：light/dark/system（system=跟随操作系统）'")),

        // V42：待治理问题台账（T11-1 二期 B）——服务端持久化每个归一键+信号簇的处置态（OPEN/CONVERTED/DISMISSED），
        // 转写成功后跨会话不再霸屏；支持定时推送去重（notified_at）与重新打开（reopen 清空 resolved/notified）。
        // 唯一键含 key_hash（normalized_key 全长 SHA-256），避免长串截断碰撞风险。
        // CREATE TABLE IF NOT EXISTS 幂等；配合 applyIfAbsent 只执行一次（全新库由 metadata-schema.sql 建）。
        new MigrationStep("V2026_10_governance_issue", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS governance_issue ("
                        + " id BIGINT NOT NULL AUTO_INCREMENT,"
                        + " tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）',"
                        + " datasource_id BIGINT NOT NULL COMMENT 'data_source.id',"
                        + " `signal` VARCHAR(32) NOT NULL COMMENT 'GovernanceSignal 枚举名',"
                        + " normalized_key VARCHAR(512) NOT NULL COMMENT '归一键（截断至 512 展示/查询用）',"
                        + " key_hash CHAR(64) NOT NULL COMMENT 'normalized_key 全长 SHA-256 十六进制（唯一键参与列，防碰撞）',"
                        + " status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CONVERTED/DISMISSED',"
                        + " question VARCHAR(512) DEFAULT NULL COMMENT '最近一次代表问法（推送/展示用）',"
                        + " occurrences INT NOT NULL DEFAULT 0 COMMENT '最近一次扫描出现次数',"
                        + " ref_type VARCHAR(16) DEFAULT NULL COMMENT '处置产出类型：METRIC/FEWSHOT',"
                        + " ref_id BIGINT DEFAULT NULL COMMENT '转写产出 id',"
                        + " dismiss_reason VARCHAR(255) DEFAULT NULL COMMENT '忽略原因',"
                        + " first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近被扫描命中',"
                        + " resolved_at DATETIME DEFAULT NULL COMMENT '转为处置态的时间',"
                        + " resolved_by BIGINT DEFAULT NULL COMMENT '处置人 uid',"
                        + " notified_at DATETIME DEFAULT NULL COMMENT '最近推送时间（去重闸）',"
                        + " PRIMARY KEY (id),"
                        + " UNIQUE KEY uk_gov_issue (tenant_id, datasource_id, key_hash, `signal`),"
                        + " KEY idx_gov_issue_tenant_status (tenant_id, status)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='待治理问题台账（T11-1 二期 B）'")),

        // V43：反馈修正采集（T11-2）——query_history 补三列，用户点踩时可选采集"期望正确 SQL/口径说明/错因"，
        // 作为治理 THUMBS_DOWN 行转写预填的高价值草稿（绝不自动进少样本/指标，必经人工复核）。
        // 与 feedback/feedback_at 同一加列范式（addColumnIfMissing 幂等）；老记录三列均 NULL，等价旧行为，升级后不变。
        new MigrationStep("V2026_11_feedback_correction", () -> {
            ctx.addColumnIfMissing("query_history", "expected_sql",
                    "expected_sql TEXT DEFAULT NULL COMMENT '点踩时用户填的期望正确 SQL（可空，T11-2）'");
            ctx.addColumnIfMissing("query_history", "feedback_note",
                    "feedback_note VARCHAR(500) DEFAULT NULL COMMENT '点踩时用户的口径说明/错因（可空，T11-2）'");
            ctx.addColumnIfMissing("query_history", "feedback_reason",
                    "feedback_reason VARCHAR(32) DEFAULT NULL COMMENT '点踩错因分类 WRONG_NUMBER/WRONG_METRIC/WRONG_FILTER/OTHER（可空，T11-2）'");
        }),

        // V44：定时报表（T12-1）——把已存查询模板固化为日/周/小时定时任务，确定性重跑并推送站内信/邮件。
        // 执行流水不另建表（复用 query_record 的 template_id 埋点）；last_run_at 既是展示位也是到期幂等锚点。
        // CREATE TABLE IF NOT EXISTS 幂等；配合 applyIfAbsent 只执行一次（已有库启动即补齐，全新库由 metadata-schema.sql 建）。
        new MigrationStep("V2026_12_scheduled_report", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS scheduled_report ("
                        + " id BIGINT NOT NULL AUTO_INCREMENT,"
                        + " tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）',"
                        + " uid BIGINT NOT NULL COMMENT '创建人 app_user.id（执行身份锚点）',"
                        + " name VARCHAR(64) NOT NULL COMMENT '报表名（同租户同人唯一）',"
                        + " template_id BIGINT NOT NULL COMMENT '引用的 saved_query.id',"
                        + " params_json VARCHAR(1000) NULL COMMENT '模板参数固定值 JSON（空参模板留空）',"
                        + " freq VARCHAR(16) NOT NULL COMMENT '预置频率：DAILY/WEEKLY/HOURLY',"
                        + " run_time VARCHAR(5) NULL COMMENT '执行时刻 HH:mm（DAILY/WEEKLY 用；HOURLY 忽略）',"
                        + " weekdays VARCHAR(13) NULL COMMENT 'ISO 周几集 1,3,5（WEEKLY 用）',"
                        + " channel VARCHAR(16) NOT NULL DEFAULT 'INAPP' COMMENT '推送渠道 INAPP/EMAIL/BOTH',"
                        + " inapp_uids VARCHAR(500) NULL COMMENT '站内信接收人 user id 逗号分隔（空=只发创建人）',"
                        + " email_targets VARCHAR(500) NULL COMMENT '邮件收件人（EMAIL/BOTH 必填）',"
                        + " enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=启用',"
                        + " last_run_at DATETIME NULL COMMENT '上轮执行时刻（到期判定与幂等锚点）',"
                        + " last_status VARCHAR(16) NULL COMMENT 'OK/FAILED/SKIPPED',"
                        + " last_error VARCHAR(500) NULL COMMENT '失败摘要（不含收件人）',"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + " PRIMARY KEY (id),"
                        + " UNIQUE KEY uk_scheduled_report_name (tenant_id, uid, name),"
                        + " KEY idx_scheduled_report_enabled (tenant_id, enabled)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定时报表任务（T12-1）'")),

        // V45：结构变更感知快照（T12-3）——每数据源一行上轮表/列集合，重扫时与之 diff 产出
        // 删表/删列事件→反查受影响查询模板/指标并预警（首轮只建基准）。首轮之前无任何行为变化。
        // CREATE TABLE IF NOT EXISTS 幂等；全新库由 metadata-schema.sql 建。
        new MigrationStep("V2026_13_schema_snapshot", () -> ctx.jdbc().execute(
                "CREATE TABLE IF NOT EXISTS schema_snapshot ("
                        + " id BIGINT NOT NULL AUTO_INCREMENT,"
                        + " tenant_id BIGINT NOT NULL COMMENT '所属组织（隔离键）',"
                        + " datasource_id BIGINT NOT NULL COMMENT 'data_source.id',"
                        + " schema_json MEDIUMTEXT NOT NULL COMMENT '表→列集合序列化 JSON（小写归一）',"
                        + " table_count INT NOT NULL DEFAULT 0 COMMENT '表数（摘要展示用）',"
                        + " col_count INT NOT NULL DEFAULT 0 COMMENT '列数（摘要展示用）',"
                        + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次成功扫描时刻',"
                        + " PRIMARY KEY (id),"
                        + " UNIQUE KEY uk_schema_snapshot_ds (tenant_id, datasource_id)"
                        + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='结构变更感知快照（T12-3）'")),

        // V46：租户级共享查询模板——saved_query 加可见性列，PRIVATE=仅本人（存量行为逐字节不变），
        // SHARED=同租户成员可见可跑（执行仍走成员自身 ACL/脱敏上下文，不升权）。
        // DEFAULT 'PRIVATE' 使存量行自动落回旧语义；与 metadata-schema.sql 双写。
        new MigrationStep("V2026_14_saved_query_visibility", () ->
                ctx.addColumnIfMissing("saved_query", "visibility",
                        "visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' "
                                + "COMMENT '可见性：PRIVATE=仅本人 / SHARED=组织共享（成员可查/可跑/可设定时）'"))
        );
    }
}
