package com.aibi.common.exception;

/**
 * 全局业务错误码。与《前后端架构设计文档》5.2 错误码规范保持一致。
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // 4xxxx：客户端 / 认证 / 权限 / 配额
    UNAUTHORIZED(40001, "未认证或登录已过期"),
    PARAM_ERROR(40002, "参数错误"),
    UNSUPPORTED_FILE_TYPE(40003, "不支持的文件类型"),
    FILE_TOO_LARGE(40004, "文件大小超过限制"),
    FORBIDDEN(40301, "无权限"),
    TENANT_MISSING(40303, "缺少租户上下文"),
    TENANT_EXPIRED(40304, "租户使用期限已到期"),
    TENANT_DISABLED(40305, "租户已被停用"),

    // 403xx：RBAC 与组织协同（见《角色权限体系与组织协同设计》6.8）
    TEAM_MISSING(40306, "请先选择团队"),
    INVITE_INVALID(40307, "邀请不存在或已被撤销"),
    INVITE_EXHAUSTED(40308, "邀请已失效或席位不足"),
    INVITE_EXPIRED(40309, "邀请已过期"),
    RESOURCE_FORBIDDEN(40310, "资源不存在或无权访问"),
    SESSION_REVOKED(40311, "权限已变更，请重新登录"),
    DATA_SCOPE_DENIED(40312, "无权访问该范围的数据"),
    ROLE_GRANT_ESCALATION(40313, "不可授予超出自身权限的角色"),
    OWNER_TRANSFER_REQUIRED(40314, "请先转让组织所有权"),
    IDENTITY_SELECT_REQUIRED(40315, "请选择要进入的组织"),
    ASSIST_NOT_APPROVED(40316, "协助授权未获组织同意"),
    SEAT_LIMIT_EXCEEDED(40317, "组织席位已满"),
    CUSTOM_ROLE_LIMIT(40318, "自定义角色数量已达套餐上限"),
    CUSTOM_ROLE_IN_USE(40319, "自定义角色仍有成员持有，不可删除"),
    CUSTOM_ROLE_BASE_INVALID(40320, "自定义角色不能基于该内置角色"),
    TABLE_FORBIDDEN(40321, "无权访问该数据表"),

    DATASOURCE_NOT_FOUND(40401, "数据源不存在或无权访问"),
    FREE_LIMIT_EXCEEDED(42901, "免费版问数次数已用尽"),
    TOO_MANY_REQUESTS(42902, "系统繁忙，请稍后重试"),
    TOTAL_LIMIT_EXCEEDED(42903, "累计生成次数已用尽"),
    DATASOURCE_LIMIT_EXCEEDED(42904, "数据源数量已达上限"),

    // 5xxxx：服务端 / 数据源 / SQL / 查询
    SYSTEM_ERROR(50000, "系统繁忙，请稍后再试"),
    DATASOURCE_CONNECT_FAILED(50001, "数据源连接失败"),
    SQL_GENERATE_FAILED(50002, "SQL 生成或执行失败"),
    QUERY_TIMEOUT(50003, "查询超时"),
    LLM_CONFIG_INVALID(50004, "大模型配置无效或不可用"),
    FILE_UPLOAD_FAILED(50005, "文件上传或解析失败"),
    // T10-1 执行前代价预估熔断：SQL 尚未下发数据库即被静态判定为大概率扫全表/笛卡尔/超预算而主动拒绝
    QUERY_COST_TOO_HIGH(50006, "查询代价过高，已为您拦截");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
