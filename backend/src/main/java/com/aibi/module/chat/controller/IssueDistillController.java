package com.aibi.module.chat.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.chat.distill.CostPatrolResult;
import com.aibi.module.chat.distill.CostPatrolService;
import com.aibi.module.chat.distill.DispositionReq;
import com.aibi.module.chat.distill.DistillReport;
import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceIssueLedgerService;
import com.aibi.module.chat.distill.IssueDistiller;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.semantic.patrol.MetaPatrolResult;
import com.aibi.module.semantic.patrol.MetaPatrolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 问题沉淀接口（T11-1）：「问数调优中心」的「待治理问题」只读 + 处置写侧。
 *
 * <p>一期：{@code GET /api/chat/tuning/issues} 只读聚合。
 * <p>二期 A：操作列转写复用既有 create（本控制器不涉及）。
 * <p>二期 B：{@code POST} 处置端点（convert/dismiss/reopen）只改台账状态，不改数据资产。
 * <p>T11-4：{@code POST /api/chat/tuning/meta-patrol} 元数据巡检（纯规则零 LLM），命中行进同一台账，
 * {@code /issues} 读侧自动合入巡检行（排蒸馏簇之后）。
 * <p>T12-4：{@code POST /api/chat/tuning/cost-patrol} 成本巡检（窗口内成功历史聚簇出物化候选/慢查询/
 * 重复问法建议），命中行同样进台账，{@code /issues} 读侧自动合入（排巡检行之后）。
 *
 * <p><b>权限</b>：复用 {@code tuning:panel:read}（YYYNNN 仅管理角色）。
 * <p><b>红线</b>：台账写端点只改自己表的 status/ref 列，绝不触碰 metric/fewshot。
 */
@RestController
@RequestMapping("/api/chat/tuning")
public class IssueDistillController {

    private final IssueDistiller issueDistiller;
    private final GovernanceIssueLedgerService ledger;
    private final MetaPatrolService metaPatrolService;
    private final CostPatrolService costPatrolService;
    private final AibiConfig aibiConfig;

    public IssueDistillController(IssueDistiller issueDistiller,
                                  GovernanceIssueLedgerService ledger,
                                  MetaPatrolService metaPatrolService,
                                  CostPatrolService costPatrolService,
                                  AibiConfig aibiConfig) {
        this.issueDistiller = issueDistiller;
        this.ledger = ledger;
        this.metaPatrolService = metaPatrolService;
        this.costPatrolService = costPatrolService;
        this.aibiConfig = aibiConfig;
    }

    /**
     * 立即分析问题沉淀清单。
     *
     * @param datasourceId 数据源过滤（可空=本租户全数据源）
     * @param includeHandled 是否包含已处置（默认 false=仅 OPEN）
     */
    @GetMapping("/issues")
    @RequiresPermission("tuning:panel:read")
    public R<DistillReport> issues(@RequestParam(required = false) Long datasourceId,
                                   @RequestParam(defaultValue = "false") boolean includeHandled) {
        DistillReport report = issueDistiller.distillForTenant(datasourceId);
        // T11-4：合入台账巡检行（排蒸馏簇之后；includeHandled 语义在台账侧同源过滤）
        List<GovernanceIssue> merged = new ArrayList<>(report.issues());
        merged.addAll(ledger.listMetaPatrol(includeHandled));
        // T12-4：合入台账成本巡检行（排最后——建议不及故障紧急，与枚举声明序一致）
        merged.addAll(ledger.listCostPatrol(includeHandled));
        if (!includeHandled && !merged.isEmpty()) {
            merged = merged.stream()
                    .filter(i -> i.status() == null || "OPEN".equals(i.status()))
                    .toList();
        }
        return R.ok(new DistillReport(merged, report.stats(), report.since()));
    }

    /**
     * 元数据巡检（T11-4）：扫语义层与指标资产，确定性识别血缘断裂/同名不同口径/注释缺失，命中行进台账。
     *
     * <p>门控 {@code METRIC / meta-patrol}（默认开）：关闭则业务码 40301 拒用。同步执行（上限 5 个数据源）。
     *
     * @param datasourceId 指定数据源（可空=本租户启用数据源前 5 个）
     */
    @PostMapping("/meta-patrol")
    @RequiresPermission("tuning:panel:read")
    public R<MetaPatrolResult> metaPatrol(@RequestParam(required = false) Long datasourceId) {
        if (!aibiConfig.metaPatrolEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "元数据巡检未启用（METRIC / meta-patrol），请联系管理员开启");
        }
        return R.ok(metaPatrolService.patrol(datasourceId));
    }

    /**
     * 成本巡检（T12-4）：对窗口内成功问数历史按归一问法聚簇，确定性识别物化候选/慢查询/重复问法，
     * 命中行进台账（转指标即物化出口，必经人工确认）。
     *
     * <p>门控 {@code METRIC / cost-patrol}（默认开）：关闭则业务码 40301 拒用。同步执行，只读自身历史表。
     *
     * @param datasourceId 指定数据源（可空=本租户全部数据源，按行 ds 分组聚簇）
     */
    @PostMapping("/cost-patrol")
    @RequiresPermission("tuning:panel:read")
    public R<CostPatrolResult> costPatrol(@RequestParam(required = false) Long datasourceId) {
        if (!aibiConfig.costPatrolEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "成本巡检未启用（METRIC / cost-patrol），请联系管理员开启");
        }
        return R.ok(costPatrolService.patrol(datasourceId));
    }

    /** 二期 B：转写成功后记录台账（只改 governance_issue，不改数据资产）。 */
    @PostMapping("/issues/convert")
    @RequiresPermission("tuning:panel:read")
    public R<Void> convert(@RequestBody DispositionReq req) {
        AccessContext ctx = AccessContextHolder.require();
        ledger.markConverted(req.datasourceId(), req.normalizedKey(), req.signal(),
                req.refType(), req.refId(), ctx.requireUid());
        return R.ok(null);
    }

    /** 二期 B：忽略某条治理建议。 */
    @PostMapping("/issues/dismiss")
    @RequiresPermission("tuning:panel:read")
    public R<Void> dismiss(@RequestBody DispositionReq req) {
        AccessContext ctx = AccessContextHolder.require();
        ledger.markDismissed(req.datasourceId(), req.normalizedKey(), req.signal(),
                req.reason(), ctx.requireUid());
        return R.ok(null);
    }

    /** 二期 B：重新打开已处置的治理建议。 */
    @PostMapping("/issues/reopen")
    @RequiresPermission("tuning:panel:read")
    public R<Void> reopen(@RequestBody DispositionReq req) {
        AccessContext ctx = AccessContextHolder.require();
        ledger.reopen(req.id(), ctx.requireUid());
        return R.ok(null);
    }
}
