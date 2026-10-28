package com.aibi.module.report.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.query.entity.SavedQuery;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.aibi.module.query.service.QueryService;
import com.aibi.module.report.dto.ReportDtos;
import com.aibi.module.report.entity.ScheduledReport;
import com.aibi.module.report.mapper.ScheduledReportMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 定时报表服务（T12-1）：任务 CRUD + 单轮执行编排（{@link #runOnce}）。
 *
 * <p><b>执行内核零新增</b>：跑模板完全复用 {@link QueryService#runTemplate}（:param 绑定 →
 * 只读校验 → 系统库拦截 → 表级硬拦 → 超时/行数上限 → 脱敏 → 落 query_record 埋点）。
 * 调用前置约定：<b>当前线程 AccessContext 必须是「租户 + 创建人 uid」</b>——
 * 调度侧 {@code ReportScheduler} 逐报表设置、手动侧 HTTP 上下文即属主本人，
 * 于是 {@code requireOwnTemplate} 自然通过、历史按创建人落库（天然审计）。
 * 此约定是本类不重复做归属校验之外的第二道墙：{@link #runOnce} 仍显式核对
 * {@code 模板.uid == 报表.uid}，模板被删/易主一律 SKIPPED 不外发。
 *
 * <p><b>投递语义（设计稿 §6）</b>：站内信收件人 = {@code inapp_uids} ∪ 创建人，逐条写
 * {@code notify_message}（ruleId/recordId 置空——报表不是告警），写库失败只 warn <b>永不抛</b>；
 * 邮件走 {@link ReportMailSender}，SMTP 未配置 → 跳过外发并在 last_error 记
 * 「邮件渠道未配置，仅站内」，<b>不视为整单失败</b>（渠道不可用 ≠ 报表失败）。
 *
 * <p><b>红线</b>：last_error 与日志只存异常 message/注记，不落收件人清单；执行零 LLM、不占问数配额。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 频率闭集（拍板 P4：只做三档预置，自定义 cron 留二期）。 */
    public static final Set<String> FREQS = Set.of("DAILY", "WEEKLY", "HOURLY");
    /** 渠道闭集。 */
    public static final Set<String> CHANNELS = Set.of("INAPP", "EMAIL", "BOTH");
    /** 执行结果三态。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** last_error 列宽（超长截断，与 DDL VARCHAR(500) 对齐）。 */
    private static final int ERROR_MAX = 500;

    private final ScheduledReportMapper reportMapper;
    private final SavedQueryMapper savedQueryMapper;
    private final QueryService queryService;
    private final NotifyMessageMapper messageMapper;
    private final ReportMailSender mailSender;
    private final ObjectMapper objectMapper;

    public ReportService(ScheduledReportMapper reportMapper, SavedQueryMapper savedQueryMapper,
                         QueryService queryService, NotifyMessageMapper messageMapper,
                         ReportMailSender mailSender, ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.savedQueryMapper = savedQueryMapper;
        this.queryService = queryService;
        this.messageMapper = messageMapper;
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
    }

    // ==================== CRUD（仅属主） ====================

    /** 我的报表列表（拦截器已按当前租户注入，这里再按 uid 收敛到本人）。 */
    public List<ReportDtos.ReportVO> listMine(Long uid) {
        List<ScheduledReport> rows = reportMapper.selectList(new LambdaQueryWrapper<ScheduledReport>()
                .eq(ScheduledReport::getUid, uid)
                .orderByDesc(ScheduledReport::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 模板名批量补齐（一次查询，不让删除判断逐行打库）
        Set<Long> tplIds = new LinkedHashSet<>();
        for (ScheduledReport r : rows) {
            if (r.getTemplateId() != null) {
                tplIds.add(r.getTemplateId());
            }
        }
        Map<Long, String> names = new java.util.HashMap<>();
        if (!tplIds.isEmpty()) {
            for (SavedQuery t : savedQueryMapper.selectBatchIds(tplIds)) {
                names.put(t.getId(), t.getName());
            }
        }
        List<ReportDtos.ReportVO> out = new ArrayList<>(rows.size());
        for (ScheduledReport r : rows) {
            out.add(ReportDtos.ReportVO.from(r, names.get(r.getTemplateId())));
        }
        return out;
    }

    /** 创建报表任务；返回新 id。 */
    public Long create(ReportDtos.ReportUpsertCmd cmd, Long tenantId, Long uid) {
        validate(cmd);
        assertTemplateUsableBy(cmd.templateId(), tenantId, uid);
        if (cmd.name().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报表名不能超过 64 字符");
        }
        // 名称唯一预检（DB 唯一键仍是最终兜底）
        Long dup = reportMapper.selectCount(new LambdaQueryWrapper<ScheduledReport>()
                .eq(ScheduledReport::getUid, uid)
                .eq(ScheduledReport::getName, cmd.name().trim()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "已存在同名报表：" + cmd.name().trim());
        }
        ScheduledReport r = new ScheduledReport();
        applyCmd(r, cmd);
        r.setTenantId(tenantId);
        r.setUid(uid);
        r.setEnabled(cmd.enabled() == null ? true : cmd.enabled());
        reportMapper.insert(r);
        log.info("定时报表已创建 id={} name={} freq={} uid={}", r.getId(), r.getName(), r.getFreq(), uid);
        return r.getId();
    }

    /** 覆盖式更新（含启停并入）；仅属主。 */
    public void update(Long id, ReportDtos.ReportUpsertCmd cmd, Long uid) {
        ScheduledReport r = requireOwn(id, uid);
        validate(cmd);
        assertTemplateUsableBy(cmd.templateId(), r.getTenantId(), uid);
        Long dup = reportMapper.selectCount(new LambdaQueryWrapper<ScheduledReport>()
                .eq(ScheduledReport::getUid, uid)
                .eq(ScheduledReport::getName, cmd.name().trim())
                .ne(ScheduledReport::getId, r.getId()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "已存在同名报表：" + cmd.name().trim());
        }
        applyCmd(r, cmd);
        if (cmd.enabled() != null) {
            r.setEnabled(cmd.enabled());
        }
        // 只回写业务列：last_* 三列保持原值（MP updateById 非空更新，null 不覆盖；
        // 这里 r 由 requireOwn 载出，last_* 原样带回，天然不丢执行锚点）
        reportMapper.updateById(r);
    }

    /** 删除报表任务；仅属主。 */
    public void delete(Long id, Long uid) {
        requireOwn(id, uid);
        reportMapper.deleteById(id);
    }

    // ==================== 执行编排 ====================

    /**
     * 手动立即执行（run-now 端点）：与调度同一条 {@link #runOnce} 链路，验证入口语义一致。
     *
     * @return 本轮执行状态（OK/FAILED/SKIPPED），前端 toast 直读
     */
    public String runNow(Long id, Long uid) {
        ScheduledReport r = requireOwn(id, uid);
        return runOnce(r, LocalDateTime.now());
    }

    /**
     * 单轮执行：载模板 → 确定性重跑 → 渲染 → 投递 → 回写三字段。<b>本方法不抛</b>，
     * 一切异常折进 {@code last_status=FAILED + last_error}（调度侧一轮多报表，单个失败绝不反噬整轮）。
     *
     * <p>前置约定见类注释：调用线程的 AccessContext 必须为「报表租户 + 创建人 uid」。
     */
    public String runOnce(ScheduledReport report, LocalDateTime runAt) {
        String status;
        String error = null;
        try {
            SavedQuery tpl = report.getTemplateId() == null ? null
                    : savedQueryMapper.selectById(report.getTemplateId());
            if (tpl == null || !templateUsable(tpl, report.getTenantId(), report.getUid())) {
                // 模板被删/易主且非共享：不外发（设计稿 §6-1），标记 SKIPPED 供管理页提示重建
                status = STATUS_SKIPPED;
                error = "来源模板不存在或归属已变更";
            } else {
                // 相对时间变量（T12-1b）按本次执行锚点展开；未知 token 抛错折进 FAILED，宁停不错跑
                Map<String, Object> params = ReportParamExpander.expand(
                        parseParams(report.getParamsJson()), runAt);
                QueryDtos.QueryRunVO vo = queryService.runTemplate(report.getTemplateId(), params);
                String body = ReportRenderer.render(report.getName(), tpl.getName(), runAt, vo == null ? null : vo.result());
                Delivery d = deliver(report, body);
                status = d.failed ? STATUS_FAILED : STATUS_OK;
                error = d.note;
            }
        } catch (Exception e) {
            status = STATUS_FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("定时报表执行失败 id={}({}): {}", report.getId(), report.getName(), error);
        }
        writeBack(report, runAt, status, error);
        return status;
    }

    // ==================== 投递 ====================

    /** 投递结果：整单是否失败 + 写入 last_error 的注记（可为 null）。 */
    private record Delivery(boolean failed, String note) {
    }

    private Delivery deliver(ScheduledReport report, String body) {
        String channel = normalizeChannel(report.getChannel());
        boolean wantInapp = !"EMAIL".equals(channel);
        boolean wantEmail = !"INAPP".equals(channel);
        String subject = "[aibi 报表] " + report.getName();

        int inappWritten = 0;
        if (wantInapp) {
            Set<Long> recipients = new LinkedHashSet<>(parseUids(report.getInappUids()));
            recipients.add(report.getUid()); // 创建人永不丢
            for (Long uid : recipients) {
                NotifyMessage m = new NotifyMessage();
                m.setTenantId(report.getTenantId());
                m.setUid(uid);
                // ruleId/recordId 置空：报表不是告警，不占告警关联列（拍板 P3）
                m.setSeverity("INFO");
                m.setTitle(subject);
                m.setContent(body);
                m.setIsRead(false);
                m.setCreatedAt(LocalDateTime.now());
                try {
                    messageMapper.insert(m);
                    inappWritten++;
                } catch (Exception e) {
                    // 站内信写库失败只 warn 永不抛（同告警默认旁路语义）
                    log.warn("报表站内信写入失败 report={} uid={}: {}", report.getId(), uid, e.getMessage());
                }
            }
        }

        String note = null;
        boolean emailOk = true;
        if (wantEmail) {
            if (!mailSender.available()) {
                // 渠道不可用 ≠ 报表失败（设计稿 §6-4）：BOTH 回落纯站内只记注记；
                // EMAIL-only 且什么都没送出 → 判失败（OK 的语义是「投递达成」，不能假装成功）
                if (wantInapp) {
                    note = "邮件渠道未配置，仅站内";
                } else {
                    emailOk = false;
                    note = "邮件渠道未配置，本次未投递";
                }
            } else {
                try {
                    mailSender.send(report.getEmailTargets(), subject, body);
                } catch (Exception e) {
                    emailOk = false;
                    note = "邮件发送失败: " + e.getMessage();
                }
            }
        }

        boolean inappOk = !wantInapp || inappWritten > 0;
        if (wantInapp && !inappOk) {
            note = (note == null ? "" : note + "; ") + "站内信写入失败（0 条落库）";
        }
        // 整单失败判据：所图渠道全没走通才失败；BOTH 任一走通即 OK（另一路折进注记）
        boolean failed;
        if (wantInapp && wantEmail) {
            failed = !inappOk && !emailOk;
        } else if (wantInapp) {
            failed = !inappOk;
        } else {
            failed = !emailOk;
        }
        return new Delivery(failed, note);
    }

    /** 回写执行三字段：只带 id + 三列的瘦对象更新，绝不把业务列的旧副本压回去。 */
    private void writeBack(ScheduledReport report, LocalDateTime runAt, String status, String error) {
        ScheduledReport upd = new ScheduledReport();
        upd.setId(report.getId());
        upd.setLastRunAt(runAt);
        upd.setLastStatus(status);
        // MP updateById 跳过 null 列：成功轮次用空串显式清旧错，防 last_error 长挂
        String trimmed = error == null ? "" : (error.length() > ERROR_MAX ? error.substring(0, ERROR_MAX) : error);
        upd.setLastError(trimmed);
        try {
            reportMapper.updateById(upd);
        } catch (Exception e) {
            log.warn("定时报表状态回写失败 id={}: {}", report.getId(), e.getMessage());
        }
    }

    // ==================== 校验与解析 ====================

    /** 跨字段条件校验收口（闭集 + 必填矩阵），文案可控。 */
    private void validate(ReportDtos.ReportUpsertCmd cmd) {
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报表名不能为空");
        }
        if (cmd.templateId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须选择已保存的查询模板");
        }
        String freq = cmd.freq() == null ? "" : cmd.freq().trim().toUpperCase(Locale.ROOT);
        if (!FREQS.contains(freq)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "频率仅支持 DAILY/WEEKLY/HOURLY");
        }
        if (!"HOURLY".equals(freq)) {
            if (ReportDueJudge.parseRunTime(cmd.runTime()) == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "执行时刻格式须为 HH:mm");
            }
            if ("WEEKLY".equals(freq) && ReportDueJudge.parseWeekdays(cmd.weekdays()).isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "每周报表须至少选择一个周几（如 1,3,5）");
            }
        }
        if (cmd.paramsJson() != null && !cmd.paramsJson().isBlank()) {
            // 入口即验 JSON 合法性 + 时间变量 dry-run（坏变量当场拒，不等夜间执行才发现）
            ReportParamExpander.assertValid(parseParams(cmd.paramsJson()));
        }
        String channel = normalizeChannel(cmd.channel());
        if (!CHANNELS.contains(channel)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "渠道仅支持 INAPP/EMAIL/BOTH");
        }
        if(!"INAPP".equals(channel)
                && (cmd.emailTargets() == null || cmd.emailTargets().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "邮件/双渠道必须填写收件人");
        }
    }

    /** 模板必须对该用户可用：属主本人或同租户组织共享（与 QueryService.requireUsableTemplate 同口径）。 */
    private void assertTemplateUsableBy(Long templateId, Long tenantId, Long uid) {
        SavedQuery tpl = savedQueryMapper.selectById(templateId);
        if (tpl == null || !templateUsable(tpl, tenantId, uid)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在或无权访问");
        }
    }

    /**
     * 报表视角的模板可用性：属主本人，或<b>同租户组织共享</b>。
     *
     * <p>共享模板不另检门控：门控（ASK / shared-templates）只收 QueryService 新建入口，
     * 存量共享模板与已建共享报表继续可用（fail-soft，关开关不破坏既有资产）；
     * 执行时仍走使用者自身的表级 ACL/脱敏上下文，共享 SQL 不升权。
     */
    static boolean templateUsable(SavedQuery tpl, Long tenantId, Long uid) {
        if (tpl == null) {
            return false;
        }
        if (uid != null && uid.equals(tpl.getUid())) {
            return true;
        }
        return QueryService.isShared(tpl)
                && tenantId != null && tenantId.equals(tpl.getTenantId());
    }

    private ScheduledReport requireOwn(Long id, Long uid) {
        ScheduledReport r = reportMapper.selectById(id);
        if (r == null || !uid.equals(r.getUid())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "报表不存在");
        }
        return r;
    }

    /** 命令字段落到实体（freq/channel 归一大写；HOURLY 清掉无意义的时刻/周几列防脏数据）。 */
    private void applyCmd(ScheduledReport r, ReportDtos.ReportUpsertCmd cmd) {
        r.setName(cmd.name().trim());
        r.setTemplateId(cmd.templateId());
        r.setParamsJson(cmd.paramsJson() == null || cmd.paramsJson().isBlank() ? null : cmd.paramsJson().trim());
        String freq = cmd.freq().trim().toUpperCase(Locale.ROOT);
        r.setFreq(freq);
        if ("HOURLY".equals(freq)) {
            r.setRunTime(null);
            r.setWeekdays(null);
        } else {
            r.setRunTime(cmd.runTime().trim());
            r.setWeekdays("WEEKLY".equals(freq) ? cmd.weekdays().trim() : null);
        }
        r.setChannel(normalizeChannel(cmd.channel()));
        r.setInappUids(cmd.inappUids() == null || cmd.inappUids().isBlank() ? null : cmd.inappUids().trim());
        r.setEmailTargets(cmd.emailTargets() == null || cmd.emailTargets().isBlank() ? null : cmd.emailTargets().trim());
    }

    private static String normalizeChannel(String channel) {
        return channel == null || channel.isBlank() ? "INAPP" : channel.trim().toUpperCase(Locale.ROOT);
    }

    /** params_json → 参数表；非法 JSON 直接拒（创建时已验，执行时防御再兜一层）。 */
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(paramsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板参数 JSON 不合法: " + e.getMessage());
        }
    }

    /** 站内信接收人 id 串解析（宽容分隔同告警口径，非法片段跳过）。 */
    static List<Long> parseUids(String raw) {
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split("[,，;；\\s]+")) {
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("忽略非法站内信接收人 id 片段");
            }
        }
        return out;
    }
}
