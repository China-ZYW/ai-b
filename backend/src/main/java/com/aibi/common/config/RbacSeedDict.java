package com.aibi.common.config;

/**
 * RBAC 种子字典（T8.1 补账拆出自 RbacSeeder，数据原样平移）。
 *
 * <p><b>两条不可动摇的约定</b>：
 * <ol>
 *   <li><b>逐条 {@code insertIfMissing}，绝不用"全表非空即跳过"</b>。本项目已踩过这个坑：
 *       {@code seedSysConfig} 用整体跳过，导致老库永远拿不到新增配置项，后来不得不另写
 *       {@code seedChartKeywordsV3} 补救。逐条按 {@code uk} 判定后插入，才能让后续版本新增的
 *       权限点自动补齐到已运行的库。</li>
 *   <li><b>{@code role_permission} 由权限矩阵派生，不另存一份清单</b>。角色→权限的授予事实
 *       只写在 {@link #TENANT_PERMISSIONS}/{@link #PLATFORM_PERMISSIONS} 的标记位里，
 *       两份清单必然随时间发散，而发散的表现是"某个角色莫名少了一个按钮"，极难定位。</li>
 * </ol>
 *
 * <p>新增权限点/角色只改本类的数组，消费方（{@link RbacDictSeeder}）按标记位派生全部关联。
 */
final class RbacSeedDict {

    /** 组织角色码顺序，与权限矩阵标记串的 6 个位一一对应（顺序即 §3.3 的权限宽窄降序）。 */
    static final String[] ORG_ROLES = {"OWNER", "ADMIN", "DATA_STEWARD", "ANALYST", "VIEWER", "BILLING"};
    static final int FLAG_ANALYST = 3;
    static final int FLAG_VIEWER = 4;

    /** 平台角色码顺序，与平台权限标记串的 4 个位一一对应。 */
    static final String[] PLATFORM_ROLES = {"SUPER_ADMIN", "OPERATOR", "AUDITOR", "SUPPORT"};

    /** 权限级别：仅平台角色可绑定。 */
    static final String LEVEL_PLATFORM = "PLATFORM";
    /** 权限级别：组织角色可绑定，团队角色不可（3.1 的硬约束）。 */
    static final String LEVEL_TENANT = "TENANT";
    /** 权限级别：团队角色亦可绑定。 */
    static final String LEVEL_TEAM = "TEAM";

    /**
     * 组织域权限点全量字典（§4.1，74 行）。
     *
     * <p>格式：{@code code|名称|type|level|menuCode|标记串}，{@code menuCode} 为空表示不驱动菜单。
     *
     * <p><b>标记串 6 位</b>，顺序为 {@code OWNER, ADMIN, DATA_STEWARD, ANALYST, VIEWER, BILLING}；
     * {@code Y}=授予、{@code N}=不授予。设计文档表格里的四种记号按下列规则收敛为二值：
     * <ul>
     *   <li>{@code ✔} → Y</li>
     *   <li>{@code △}（受组织开关或套餐约束）→ Y：权限点<b>授予</b>，约束由运行时的
     *       {@code tenant.allow_viewer_query}、套餐裁剪（3.6⑧）二次判定，不体现在字典里</li>
     *   <li>{@code ✔†}（仅对本人或本团队资源生效）→ Y：这是数据范围的收敛，不是权限点的有无</li>
     *   <li>{@code ✗} / {@code ✗‡} → N</li>
     * </ul>
     *
     * <p><b>一处与设计文档表格的有意偏离</b>：{@code team:member:write} 的 DATA_STEWARD 列在
     * §4.1 标为 {@code △§}，但 §3.3 明确写 DATA_STEWARD "<b>不含</b>成员管理"。取后者（更具体的陈述），
     * 标为 N——否则数据管理员能增删团队成员，与它的职责定义直接冲突。
     *
     * <p>{@code menu_path}/{@code menu_icon} 一律留 NULL：二者仅用于后台展示，且可由前端路由表按
     * {@code menu_code} 反查，写进字典只会多出一份需要同步维护的副本。
     */
    static final String[] TENANT_PERMISSIONS = {
            // 问数
            "chat:query:read|进入问数页|MENU|TEAM|chat|YYYYYN",
            "chat:query:execute|发起问数|API|TEAM||YYYYYN",
            "chat:query:export|导出结果|BUTTON|TEAM||YYYYNN",
            "chat:query:verify|AI 质量校验|API|TEAM||YYYYYN",
            "chat:insight:execute|AI 数据解读|API|TEAM||YYYYYN",
            "chat:suggestion:read|推荐问法|API|TEAM||YYYYYN",
            // 对话问数嵌入（第三方页面）：会把某个数据源以匿名令牌开放到外部，属对外暴露数据的敏感操作，
            // 故只授予 OWNER / ADMIN，不给数据管家与分析师（避免绕过组织审批对外开放数据）。
            "chat:embed:write|对话问数嵌入管理|API|TENANT||YYNNNN",
            // 归因分析（P0.1）：按维度成员展开业务表现，属敏感经营信息，故不授予 BILLING
            "attribution:run:execute|执行归因分析|API|TEAM||YYYYYN",
            "attribution:dimension:read|归因维度列表|API|TEAM||YYYYYN",
            "lineage:metric:read|指标血缘查看|API|TEAM||YYYYYN",
            "lineage:field:read|字段引用查看|API|TEAM||YYYYYN",
            "lineage:metric:write|血缘重建|API|TEAM||YYNNNN",
            // 数据源
            "datasource:config:read|数据源列表|MENU|TENANT|datasource|YYYYYN",
            "datasource:config:write|新增/编辑数据源|API|TENANT||YYYNNN",
            "datasource:config:delete|删除数据源|API|TENANT||YYYNNN",
            "datasource:config:test|连通性测试|API|TENANT||YYYNNN",
            "datasource:schema:read|浏览表/列结构|API|TENANT||YYYYNN",
            "datasource:file:upload|上传 CSV/Excel|API|TENANT||YYYNNN",
            // 问数调优
            "tuning:panel:read|进入问数调优|MENU|TENANT|tuning|YYYNNN",
            "semantic:meta:read|语义层查看|API|TENANT||YYYYNN",
            "semantic:meta:write|语义层编辑|API|TENANT||YYYNNN",
            "semantic:meta:init|语义层骨架初始化|API|TENANT||YYYNNN",
            "semantic:meta:auto-analysis|指标自动分析|API|TENANT||YYYNNN",
            "semantic:meta:delete|语义层删除|API|TENANT||YYYNNN",
            "fewshot:example:read|样例库查看|API|TENANT||YYYYNN",
            "fewshot:example:write|样例新增/编辑|API|TENANT||YYYNNN",
            "fewshot:example:promote|历史晋升为样例|API|TENANT||YYYNNN",
            "fewshot:example:delete|样例删除|API|TENANT||YYYNNN",
            "chart:rule:read|图表规则查看|API|TENANT||YYYYNN",
            "chart:rule:write|图表规则编辑|API|TENANT||YYYNNN",
            "config:sys:read|结果展示配置查看|API|TENANT||YYYYNN",
            "config:sys:write|结果展示配置编辑|API|TENANT||YYYNNN",
            "theme:config:write|主题令牌自定义|API|TENANT||YYYNNN",
            // 指标
            "metric:item:read|指标库|MENU|TEAM|metrics|YYYYYN",
            "metric:item:write|指标新建/编辑|API|TEAM||YYYYNN",
            "metric:item:delete|指标删除|API|TEAM||YYYYNN",
            "metric:item:refresh|指标刷新（重跑 SQL）|API|TEAM||YYYYYN",
            // 驾驶舱
            "dashboard:item:read|驾驶舱列表|MENU|TEAM|dashboards|YYYYYN",
            "dashboard:item:write|新建/编辑/布局|API|TEAM||YYYYNN",
            "dashboard:item:delete|删除驾驶舱|API|TEAM||YYYYNN",
            "dashboard:item:share|生成分享/大屏令牌|API|TEAM||YYYYNN",
            "dashboard:screen:publish|大屏公开发布|BUTTON|TEAM||YYYYNN",
            "dashboard:template:apply|套用内置模板|API|TEAM||YYYYNN",
            "dashboard:cockpit:ai|AI 自动化大屏|API|TEAM||YYYYNN",
            // 历史
            "history:query:read|查询历史|MENU|TEAM|history|YYYYYN",
            "history:query:delete|删除历史|API|TEAM||YYYYNN",
            "history:query:feedback|赞/踩反馈|API|TEAM||YYYYYN",
            // 收藏
            "favorite:item:read|我的收藏|MENU|TEAM|favorite|YYYYYN",
            "favorite:item:write|收藏/改标题|API|TEAM||YYYYYY",
            "favorite:item:delete|取消收藏|API|TEAM||YYYYYY",
            // 分享
            "share:link:read|分享管理|MENU|TEAM|shares|YYYYNN",
            "share:link:write|创建分享链接|API|TEAM||YYYYNN",
            "share:link:revoke|撤销/改密码/改有效期|API|TEAM||YYYYNN",
            "share:link:delete|删除分享|API|TEAM||YYYYNN",
            // 用量 / 手册 / 个人
            "quota:usage:read|今日配额状态|API|TEAM||YYYYYY",
            "manual:doc:read|用户手册|MENU|TEAM|manual|YYYYYY",
            "profile:self:read|个人中心|MENU|TEAM|profile|YYYYYY",
            "profile:self:write|改昵称/头像|API|TEAM||YYYYYY",
            "profile:password:write|改口令|API|TEAM||YYYYYY",
            // 成员与邀请
            "member:member:read|成员列表|MENU|TENANT|org-members|YYNNNN",
            "member:member:write|改成员资料/团队归属|API|TENANT||YYNNNN",
            "member:member:delete|移除成员|API|TENANT||YYNNNN",
            "member:invite:create|发起邀请|API|TENANT||YYNNNN",
            "member:invite:read|邀请列表|MENU|TENANT|org-invitations|YYNNNN",
            "member:invite:revoke|撤销邀请|API|TENANT||YYNNNN",
            "member:invite:approve|审批加入申请|API|TENANT||YYNNNN",
            "member:role:assign|分配/回收角色|API|TENANT||YYNNNN",
            "member:password:reset|重置成员口令|API|TENANT||YYNNNN",
            "member:self:leave|主动退出组织|API|TEAM||NYYYYY",
            // 团队与组织
            "team:team:read|团队管理页|MENU|TENANT|org-teams|YYNNNN",
            "team:team:write|建/改团队|API|TENANT||YYNNNN",
            "team:team:delete|解散团队|API|TENANT||YYNNNN",
            "team:member:write|团队成员增删改角色|API|TEAM||YYNNNN",
            "org:org:read|组织设置页|MENU|TENANT|org-settings|YYNNNY",
            "org:org:write|改组织名/默认设置|API|TENANT||YYNNNN",
            "org:seat:read|席位余量|API|TENANT||YYNNNY",
            "org:customrole:write|自定义角色|API|TENANT||YYNNNN",
            "org:org:transfer|转让所有权|API|TENANT||YNNNNN",
            "org:org:dissolve|解散组织|API|TENANT||YNNNNN",
            // 计费与审计
            "billing:usage:read|用量与账单|MENU|TENANT|org-billing|YYNNNY",
            "billing:subscription:write|续订/升套餐入口|API|TENANT||YYNNNY",
            // 审计是组织设置页内的 Tab 而非独立路由，故 menuCode 留空
            "audit:log:read|组织内审计日志|MENU|TENANT||YYNNNN",
            // 智能预警（组织设置 · 预警，页 org-alerts）：与 AlertRuleController 各端点权限点一一对应。
            // 组织矩阵一律 YYNNNN → 仅 OWNER/ADMIN；与控制器内 assertManager() 的口径一致（纵深防御）。
            "alert:rule:read|预警规则|MENU|TENANT|org-alerts|YYNNNN",
            "alert:rule:write|预警规则新建/编辑/启停|API|TENANT||YYNNNN",
            "alert:rule:delete|预警规则删除|API|TENANT||YYNNNN",
            "alert:record:read|预警触发记录|API|TENANT||YYNNNN",
            "alert:detect:execute|手动触发预警检测|API|TENANT||YYNNNN",
            // 站内信收件箱：人人可读"自己的"信（数据天然以 uid 收敛），故标记 YYYYYY；与告警规则 INAPP 渠道配套。
            "message:inbox:read|站内信收件箱|MENU|TENANT|messages|YYYYYY",
    };

    /**
     * 平台域权限点全量字典（§4.2，21 行）。
     *
     * <p>格式同上，标记串 4 位，顺序为 {@code SUPER_ADMIN, OPERATOR, AUDITOR, SUPPORT}。
     * {@code type=MENU} 表示该码同时驱动运营后台的菜单可见性（§9.2 的页面清单），
     * 接口鉴权仍按码判定，与 {@code type} 无关。
     */
    static final String[] PLATFORM_PERMISSIONS = {
            "admin:overview:read|平台概览|MENU|admin-overview|YYYY",
            "admin:tenant:read|租户列表/详情|MENU|admin-tenants|YYYY",
            "admin:tenant:write|建/改租户与席位配额|MENU|admin-seats|YYNN",
            "admin:tenant:disable|停用/启用租户|API||YYNN",
            "admin:tenant:dissolve|注销租户|API||YNNN",
            "admin:user:read|全平台账号查询|MENU|admin-users|YYYY",
            "admin:user:write|建/改账号|API||YYNN",
            "admin:user:reset|重置账号口令|API||YNNN",
            "admin:role:assign|授予平台角色|MENU|admin-platform-roles|YNNN",
            "admin:rbac:read|权限字典与接口矩阵|MENU|admin-rbac|YYNY",
            "admin:rbac:write|维护权限点/内置角色|API||YNNN",
            "admin:llm:read|大模型配置查看|MENU|admin-llm|YYNY",
            "admin:llm:write|大模型配置增删改|API||YYNN",
            "admin:config:read|平台默认配置查看|MENU|admin-config|YYYN",
            "admin:config:write|平台默认配置修改|API||YYNN",
            "admin:content:write|内容运营|MENU|admin-content|YYNN",
            "admin:manual:publish|用户手册发布|MENU|admin-manual|YYNN",
            "admin:audit:read|全平台审计日志|MENU|admin-audit|YNYN",
            "admin:invitation:read|全平台邀请治理|MENU|admin-invitations|YYYN",
            "ops:assist:request|发起代客协助申请|MENU|admin-assist|YNNY",
            // 对外 MCP 令牌管理（admin-mcp，见《对外 MCP 能力方案》）：签发/吊销个人访问令牌。
            // 只读给 OPERATOR/AUDITOR（可查看接入情况），签发只给 SUPER_ADMIN 与 OPERATOR。
            "admin:mcp:read|MCP 令牌查看|MENU|admin-mcp|YYYN",
            "admin:mcp:write|MCP 令牌签发/吊销|API||YYNN",
            "ops:diagnostic:read|只读诊断（脱敏）|API||YYYY",
    };

    /**
     * 角色字典（§3.2 / 3.3 / 3.4，13 行）。
     *
     * <p>格式：{@code code|名称|level|dataScope|inheritToChild|说明}。{@code sort} 按数组序号 ×10 生成，
     * 使平台 &gt; 组织 &gt; 团队的展示顺序稳定，后台插入新角色时有空位可用。
     *
     * <p>{@code inherit_to_child} 只对团队角色有意义：§3.4 约定 {@code TEAM_OWNER}/{@code TEAM_EDITOR}
     * 向子团队继承、{@code TEAM_VIEWER} 不继承。该值由 {@code AccessContextBuilder.resolveTeamRoles}
     * 逐角色码消费，字典缺失时按"不继承"处理。
     */
    static final String[] ROLES = {
            "SUPER_ADMIN|平台超级管理员|PLATFORM|ALL|0|全权；唯一可授出平台角色的角色",
            "OPERATOR|运营用户|PLATFORM|ALL|0|租户开通/续费/停用、席位与配额、内容运营、邀请治理",
            "AUDITOR|平台审计|PLATFORM|ALL|0|只读全平台审计、登录记录、越权拦截记录",
            "SUPPORT|技术支持|PLATFORM|ALL|0|只读诊断 + 发起代客协助授权申请",
            "OWNER|组织所有者|TENANT|ALL|0|全部组织权限点 + 转让/解散 + 无限制角色分配",
            "ADMIN|组织管理员|TENANT|ALL|0|成员/团队/邀请/角色分配（不可授出 OWNER）、数据源与调优全部",
            "DATA_STEWARD|数据管理员|TENANT|ALL|0|数据源、语义层、样例库、图表规则、展示配置、主题；不含成员管理",
            "ANALYST|分析师|TENANT|TEAM_AND_CHILD|0|问数、指标与驾驶舱 CRUD、分享、收藏、历史；数据源与调优只读",
            "VIEWER|只读成员|TENANT|TEAM|0|问数（可被组织开关关闭）、查看被授权资源、查看本人历史；不可导出/分享/建指标",
            "BILLING|计费联系人|TENANT|TENANT|0|用量与账单只读、续订入口、席位查看；无业务数据权限",
            "TEAM_OWNER|团队负责人|TEAM|TEAM_AND_CHILD|1|团队内成员增删与角色分配、团队资源全部",
            "TEAM_EDITOR|团队编辑者|TEAM|TEAM_AND_CHILD|1|团队内指标/驾驶舱/收藏/分享 CRUD、问数、团队资源读",
            "TEAM_VIEWER|团队只读|TEAM|TEAM|0|团队资源只读、问数（同 VIEWER 约束）",
    };

    private RbacSeedDict() {
    }

    /**
     * 种子数据格式自检。
     *
     * <p>标记串长度写错时，{@code charAt} 会抛 {@code StringIndexOutOfBoundsException}，
     * 堆栈里看不出是哪一行权限点出了问题；先自检可以把错误变成带完整种子文本的明确提示。
     */
    static void validate() {
        for (String seed : TENANT_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            if (f.length != 6 || f[5].length() != ORG_ROLES.length) {
                throw new IllegalStateException(
                        "组织域权限点种子格式错误（应为 code|名称|type|level|menuCode|" + ORG_ROLES.length + "位标记）: " + seed);
            }
        }
        for (String seed : PLATFORM_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            if (f.length != 5 || f[4].length() != PLATFORM_ROLES.length) {
                throw new IllegalStateException(
                        "平台域权限点种子格式错误（应为 code|名称|type|menuCode|" + PLATFORM_ROLES.length + "位标记）: " + seed);
            }
        }
        for (String role : ROLES) {
            if (role.split("\\|", -1).length != 6) {
                throw new IllegalStateException("角色种子格式错误（应为 code|名称|level|dataScope|inheritToChild|说明）: " + role);
            }
        }
    }
}
