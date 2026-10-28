package com.aibi.module.report.dto;

import com.aibi.module.report.entity.ScheduledReport;

import java.time.LocalDateTime;

/**
 * 定时报表 DTO（T12-1）。
 *
 * <p>校验刻意放在服务层而非 Bean Validation 注解：freq/channel 闭集、WEEKLY 必配周几、
 * EMAIL/BOTH 必配收件人这类<b>跨字段条件校验</b>，注解表达反而绕，统一在
 * {@code ReportService.validate} 一处收口、文案可控。
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * 创建/覆盖式更新共用命令（PUT 全字段覆盖，同模板更新先例——防改一半留一半）。
     *
     * @param name         报表名（≤64，同租户同人唯一）
     * @param templateId   引用的 saved_query.id
     * @param paramsJson   模板参数固定值 JSON（可空）
     * @param freq         DAILY / WEEKLY / HOURLY
     * @param runTime      HH:mm（DAILY/WEEKLY 必填；HOURLY 忽略）
     * @param weekdays     ISO 周几集 1,3,5（WEEKLY 必填）
     * @param channel      INAPP / EMAIL / BOTH（空=INAPP）
     * @param inappUids    站内信接收人 id 逗号分隔（空=只发创建人）
     * @param emailTargets 邮件收件人（EMAIL/BOTH 必填）
     * @param enabled      启停（空=新建默认启用/更新保持原值）
     */
    public record ReportUpsertCmd(
            String name,
            Long templateId,
            String paramsJson,
            String freq,
            String runTime,
            String weekdays,
            String channel,
            String inappUids,
            String emailTargets,
            Boolean enabled
    ) {
    }

    /**
     * 报表视图（含上轮执行结果三字段，管理页一屏看全）。
     *
     * @param templateName 来源模板名（模板已删时为占位文案，前端可据此提示重建）
     */
    public record ReportVO(
            Long id,
            String name,
            Long templateId,
            String templateName,
            String paramsJson,
            String freq,
            String runTime,
            String weekdays,
            String channel,
            String inappUids,
            String emailTargets,
            Boolean enabled,
            LocalDateTime lastRunAt,
            String lastStatus,
            String lastError,
            LocalDateTime createdAt
    ) {

        public static ReportVO from(ScheduledReport r, String templateName) {
            return new ReportVO(r.getId(), r.getName(), r.getTemplateId(),
                    templateName != null ? templateName : "（模板已删除）",
                    r.getParamsJson(), r.getFreq(), r.getRunTime(), r.getWeekdays(),
                    r.getChannel(), r.getInappUids(), r.getEmailTargets(), r.getEnabled(),
                    r.getLastRunAt(), r.getLastStatus(), r.getLastError(), r.getCreatedAt());
        }
    }
}
