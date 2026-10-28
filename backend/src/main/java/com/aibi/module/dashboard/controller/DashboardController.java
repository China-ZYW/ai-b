package com.aibi.module.dashboard.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.dto.AiLayoutRequest;
import com.aibi.module.dashboard.dto.AiLayoutVO;
import com.aibi.module.dashboard.dto.AutoDesignForm;
import com.aibi.module.dashboard.dto.DashboardDataVO;
import com.aibi.module.dashboard.dto.DashboardForm;
import com.aibi.module.dashboard.dto.DashboardMoveForm;
import com.aibi.module.dashboard.dto.DashboardShareForm;
import com.aibi.module.dashboard.dto.DashboardShareVO;
import com.aibi.module.dashboard.dto.DashboardVO;
import com.aibi.module.dashboard.dto.FromTemplateForm;
import com.aibi.module.dashboard.service.CockpitAutoAnalysisService;
import com.aibi.module.dashboard.service.DashboardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 驾驶舱/大屏接口（Feature 2）：CRUD、布局保存、批量刷新、一键自动布局、从内置模板实例化。
 *
 * <p>全部操作以当前租户为边界（{@code dashboard} 自动注入 tenant_id）。
 * {@code GET /{id}/data} 供大屏按 {@code refreshSeconds} 轮询，一次返回布局 + 各 widget 实时数据。
 *
 * <p><b>权限点（L2）</b>：本域此前只靠 {@code @CheckResourceAccess} 兜底，而资源 ACL 对"无授权行的
 * 历史资源"是 fail-open（见 {@code PermissionAspect.checkResourceAccess} 注释），叠加
 * {@code POST /api/dashboard}（新建）与 {@code GET} 系列完全没有闸门，等于"任何登录用户可读写本租户全部看板"。
 * 现按权限字典逐端点补齐，权限码与前端按钮门禁（{@code can('dashboard:item:write')} 等）严格同名同义；
 * 公开大屏分享走 {@code /api/public/dashboard/{token}}（permitAll + 独立令牌），不经过本控制器，不受影响。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CockpitAutoAnalysisService cockpitAutoAnalysisService;
    private final AibiConfig aibiConfig;

    public DashboardController(DashboardService dashboardService,
                               CockpitAutoAnalysisService cockpitAutoAnalysisService,
                               AibiConfig aibiConfig) {
        this.dashboardService = dashboardService;
        this.cockpitAutoAnalysisService = cockpitAutoAnalysisService;
        this.aibiConfig = aibiConfig;
    }

    @GetMapping
    @RequiresPermission("dashboard:item:read")
    public R<List<DashboardVO>> list() {
        return R.ok(dashboardService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission("dashboard:item:read")
    public R<DashboardVO> get(@PathVariable Long id) {
        return R.ok(dashboardService.get(id));
    }

    @PostMapping
    @RequiresPermission("dashboard:item:write")
    public R<DashboardVO> create(@RequestBody @Valid DashboardForm form) {
        return R.ok(dashboardService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission("dashboard:item:write")
    @CheckResourceAccess(type = "DASHBOARD", idParam = "id", permission = "WRITE")
    public R<DashboardVO> update(@PathVariable Long id, @RequestBody @Valid DashboardForm form) {
        return R.ok(dashboardService.update(id, form));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("dashboard:item:delete")
    @CheckResourceAccess(type = "DASHBOARD", idParam = "id", permission = "WRITE")
    public R<Void> delete(@PathVariable Long id) {
        dashboardService.delete(id);
        return R.ok();
    }

    /**
     * 大屏批量刷新：布局 + 各 widget 指标实时数据（单 widget 失败降级占位）。
     *
     * <p>{@code filter} 为可选的下钻/联动筛选 JSON（{@code {"dim":"region","value":"华东"}}）：
     * 传入后整屏按该维度切片联动。null/空白/非法按不过滤处理（见 {@code DashboardService#getData}）。
     */
    @GetMapping("/{id}/data")
    @RequiresPermission("dashboard:item:read")
    public R<DashboardDataVO> data(@PathVariable Long id,
            @RequestParam(required = false) String filter) {
        return R.ok(dashboardService.getData(id, filter));
    }

    /**
     * 移动驾驶舱到指定分组（{@code groupId} 空=移出分组）。
     *
     * <p>分组 CRUD 在 {@code DashboardGroupController}（/api/dashboard/group）；本端点只管单个看板的归属变更。
     * 走专用端点而非全量 update：编辑器保存 payload 不携带分组字段，全量 set 会把分组静默清空。
     */
    @PutMapping("/{id}/group")
    @RequiresPermission("dashboard:item:write")
    @CheckResourceAccess(type = "DASHBOARD", idParam = "id", permission = "WRITE")
    public R<DashboardVO> moveGroup(@PathVariable Long id, @RequestBody DashboardMoveForm form) {
        return R.ok(dashboardService.moveToGroup(id, form == null ? null : form.groupId()));
    }

    /** 一键自动布局：给定指标生成 12 列均衡网格驾驶舱。 */
    @PostMapping("/auto-design")
    @RequiresPermission("dashboard:item:write")
    public R<DashboardVO> autoDesign(@RequestBody @Valid AutoDesignForm form) {
        return R.ok(dashboardService.autoDesign(form));
    }

    /**
     * AI 智能设计（大模型自定义布局 + 样式填充）：给定各组件的数据形态特征与确定性算法基线，
     * 由大模型产出语义分组更合理的布局槽位与整套视觉样式。<b>不落库</b>，前端应用后再点保存。
     *
     * <p>大模型未配置 / 调用失败 / 输出非法（越界、重叠、未覆盖全部组件）时，返回
     * {@code source=algorithm} 且 slots 为空，前端沿用自带的确定性基线布局——功能永不失效。
     *
     * <p>归属 {@code dashboard:item:write}：入口在驾驶舱编辑页（该路由本身即受 write 守卫），
     * 且会消耗一次大模型调用，不宜对只读角色开放。
     */
    @PostMapping("/ai-layout")
    @RequiresPermission("dashboard:item:write")
    public R<AiLayoutVO> aiLayout(@RequestBody AiLayoutRequest form) {
        return R.ok(dashboardService.aiLayout(form));
    }

    /** 从内置模板实例化：拷贝骨架布局并按顺序绑定指标。 */
    @PostMapping("/from-template")
    @RequiresPermission("dashboard:template:apply")
    public R<DashboardVO> fromTemplate(@RequestBody @Valid FromTemplateForm form) {
        return R.ok(dashboardService.fromTemplate(form));
    }

    /**
     * 驾驶舱 AI 自动化大屏 Skill：基于某数据源的指标，自动编排图表与布局，生成可直接预览的 AI 大屏
     * （先确定性基线、再 AI 布局增强，失败回退算法方案）。受 {@code dashboard:cockpit:ai} 门禁。
     */
    @PostMapping("/ai")
    @RequiresPermission("dashboard:cockpit:ai")
    public R<CockpitAutoAnalysisService.AiDashboardResult> aiBuild(@RequestParam Long datasourceId,
            @RequestParam(required = false) String intent) {
        if (!aibiConfig.cockpitAiDashboardEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "AI 自动化大屏功能已关闭（请在系统配置中开启 COCKPIT/ai-dashboard）");
        }
        // 后台异步：立即返回"已提交"，实际编排在 analysisExecutor 线程跑，避免大屏编排阻塞请求线程
        return R.ok(cockpitAutoAnalysisService.analyzeAsync(datasourceId, intent));
    }

    /** 查询大屏分享状态（供分享对话框预填，问题2）。 */
    @GetMapping("/{id}/share")
    @RequiresPermission("dashboard:item:share")
    public R<DashboardShareVO> shareStatus(@PathVariable Long id) {
        return R.ok(dashboardService.shareStatus(id));
    }

    /**
     * 开启/更新/关闭大屏公开分享（问题2）：返回可访问的公开链接。
     *
     * <p>用 {@code dashboard:screen:publish} 而非 {@code dashboard:item:share}：前端分享对话框的「保存」按钮
     * 正是以 {@code can('dashboard:screen:publish')} 门禁的，此处与 UI 保持同一口径。
     * 两者在权限字典中的授予集合相同（OWNER/ADMIN/DATA_STEWARD/ANALYST/TEAM_OWNER/TEAM_EDITOR），
     * 故不存在可见按钮点不动的情况。
     */
    @PutMapping("/{id}/share")
    @RequiresPermission("dashboard:screen:publish")
    public R<DashboardShareVO> configShare(@PathVariable Long id, @RequestBody DashboardShareForm form) {
        return R.ok(dashboardService.configShare(id, form));
    }
}
