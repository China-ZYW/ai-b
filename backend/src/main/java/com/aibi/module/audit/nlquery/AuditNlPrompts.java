package com.aibi.module.audit.nlquery;

import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 审计 NL 槽位解析 prompt 构建（T11-3）：一次性、单轮、纯 JSON 输出。
 *
 * <p>刻意把任务定义成"翻译过滤条件"而非"写查询"：模型没有生成 SQL 的能力出口，
 * 幻觉空间被词表闭集与 few-shot 夹在中间；时间维度明确告知"系统已确定性解析"，
 * 模型只在规则盖不住时才补时间槽，减少与规则结果的打架面。
 */
public final class AuditNlPrompts {

    private AuditNlPrompts() {
    }

    /** 构建解析 prompt（调用方以 temperature=0 的 LlmRouter 通道发送，见设计稿 §5）。 */
    public static String build(String question, LocalDateTime now) {
        String actions = AuditNlDictionary.ACTIONS.stream().collect(Collectors.joining(", "));
        String hints = String.join("\n", AuditNlDictionary.ACTION_HINTS);
        return """
                你是审计日志检索的条件解析器。把用户的中文提问翻译成 JSON 过滤条件。只输出纯 JSON，不输出任何解释、不输出 SQL。

                当前时间：%s（%s）。

                JSON 字段（拿不准的一律填 null，禁止编造）：
                - "timeFrom"/"timeTo": "yyyy-MM-dd HH:mm:ss"。今天/昨天/最近N天/上周/本月等常见说法系统已自动识别，你识别不了的说法才填。
                - "username": 提问点名的人（用户名或姓名原文）。
                - "action": 动作码，只能从白名单选：%s
                - "result": OK / DENIED / FAILED 三选一（"失败"=FAILED，"被拒绝/越权"=DENIED）。
                - "module": 操作日志模块关键词（如"数据查询""用户管理"）。
                - "keyword": 提问涉及的对象词（如"订单""销售报表"），填最短核心名词，不要整句。
                - "sources": 数组，取值 AUDIT（登录/权限/管理事件）、OPS（接口操作流水）、QUERY（问数历史，"谁查了什么数据"用它）。不确定就三源全给。
                - "note": 需要告知用户的解析说明。

                动作码速查：
                %s

                规则：
                1. "谁查了/问过 XX 数据"= 问数行为，sources 给 ["QUERY"]、对象词进 keyword，不要选 action。
                2. 部门/团队/组织名（如"运营部"）不是支持的维度：忽略它，note 里说明"部门维度暂不支持，已按对象关键词收窄"。
                3. 提问同时提到人名和动作时，username 与 action 都填。

                示例：
                问："张三昨天登录失败了几次" → {"timeFrom":null,"timeTo":null,"username":"张三","action":"LOGIN_FAILED","result":null,"module":null,"keyword":null,"sources":["AUDIT"],"note":null}
                问："最近一周谁导出过审计日志" → {"timeFrom":null,"timeTo":null,"username":null,"action":"AUDIT_EXPORT","result":null,"module":null,"keyword":"审计日志","sources":["AUDIT"],"note":null}
                问："今天谁查过订单数据" → {"timeFrom":null,"timeTo":null,"username":null,"action":null,"result":null,"module":null,"keyword":"订单","sources":["QUERY"],"note":null}

                提问：%s
                """.formatted(now.format(AuditTimePhrases.TS),
                now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA),
                actions, hints, question);
    }
}
