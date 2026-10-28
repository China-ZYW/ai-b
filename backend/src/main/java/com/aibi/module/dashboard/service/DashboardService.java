package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.UserContext;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.acl.entity.ResourceAcl;
import com.aibi.module.acl.service.ResourceAclService;
import com.aibi.module.dashboard.dto.AiLayoutRequest;
import com.aibi.module.dashboard.dto.AiLayoutVO;
import com.aibi.module.dashboard.dto.AutoDesignForm;
import com.aibi.module.dashboard.dto.DashboardAccessResponse;
import com.aibi.module.dashboard.dto.DashboardDataVO;
import com.aibi.module.dashboard.dto.DashboardForm;
import com.aibi.module.dashboard.dto.DashboardFilter;
import com.aibi.module.dashboard.dto.DashboardShareForm;
import com.aibi.module.dashboard.dto.DashboardShareVO;
import com.aibi.module.dashboard.dto.DashboardVO;
import com.aibi.module.dashboard.dto.FromTemplateForm;
import com.aibi.module.dashboard.dto.MetricDataVO;
import com.aibi.module.dashboard.dto.WidgetDataVO;
import com.aibi.module.dashboard.dto.WidgetLayout;
import com.aibi.module.dashboard.entity.Dashboard;
import com.aibi.module.dashboard.entity.DashboardTemplate;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.service.ResourceGroupService;
import com.aibi.module.dashboard.mapper.DashboardMapper;
import com.aibi.module.dashboard.mapper.DashboardTemplateMapper;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aibi.module.datasource.model.QueryResult;

/**
 * 驾驶舱服务（Feature 2）：CRUD、布局保存、批量刷新、一键自动布局、从内置模板实例化。
 *
 * <p><b>隔离</b>：{@code dashboard} 由租户拦截器自动注入 {@code tenant_id}；批量刷新委派 {@link MetricService#refresh}
 * 逐 widget 重跑指标 SQL（单个失败降级为占位，不拖垮整个大屏）。{@code dashboard_template} 为全局表，所有租户共享。
 *
 * <p><b>自动布局</b>：按图表类型定尺寸（PIE 4×4、TABLE 6×3、BAR/LINE 6×4），在 12 列网格逐行填充、超出换行。
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final int DEFAULT_COLS = 12;

    private final DashboardMapper dashboardMapper;
    private final MetricMapper metricMapper;
    private final DashboardTemplateMapper templateMapper;
    /** 分组校验走共享服务（驾驶舱域），与指标库共用一套 {@code resource_group}。 */
    private final ResourceGroupService groupService;
    private final MetricService metricService;
    private final DashboardJsonSupport json;
    private final PasswordEncoder passwordEncoder;
    private final String publicBaseUrl;
    private final ResourceAclService resourceAclService;
    private final AiLayoutAdvisor aiLayoutAdvisor;

    public DashboardService(DashboardMapper dashboardMapper,
                            MetricMapper metricMapper,
                            DashboardTemplateMapper templateMapper,
                            ResourceGroupService groupService,
                            MetricService metricService,
                            DashboardJsonSupport json,
                            PasswordEncoder passwordEncoder,
                            @Value("${aibi.share.public-base-url:}") String publicBaseUrl,
                            ResourceAclService resourceAclService,
                            AiLayoutAdvisor aiLayoutAdvisor) {
        this.dashboardMapper = dashboardMapper;
        this.metricMapper = metricMapper;
        this.templateMapper = templateMapper;
        this.groupService = groupService;
        this.metricService = metricService;
        this.json = json;
        this.passwordEncoder = passwordEncoder;
        this.publicBaseUrl = publicBaseUrl;
        this.resourceAclService = resourceAclService;
        this.aiLayoutAdvisor = aiLayoutAdvisor;
    }

    /**
     * AI 智能设计：由大模型给出「布局 + 视觉样式」建议（不落库，前端应用后再点保存）。
     *
     * <p>布局规则的<b>唯一来源仍是前端确定性引擎</b>：本方法只做转发与结果的合法性把关，
     * 校验不通过时 {@code source=algorithm}，前端继续使用它自己算好的基线布局。
     *
     * @param form 组件特征 + 算法基线 + 可选风格指令
     * @return 建议；不抛异常，不可用时回退标记 {@code source=algorithm}
     */
    public AiLayoutVO aiLayout(AiLayoutRequest form) {
        if (form == null) {
            return new AiLayoutVO(List.of(), Map.of(), null, null,
                    AiLayoutVO.SRC_ALGORITHM, "请求为空，沿用确定性算法方案");
        }
        return aiLayoutAdvisor.advise(form);
    }

    public List<DashboardVO> list() {
        return dashboardMapper.selectList(Wrappers.<Dashboard>lambdaQuery().orderByDesc(Dashboard::getId))
                .stream().map(this::toVO).toList();
    }

    public DashboardVO get(Long id) {
        return toVO(require(id));
    }

    @Transactional
    public DashboardVO create(DashboardForm form) {
        Dashboard d = newDashboard(form.name(), form.description(), form.theme(),
                form.cols(), form.refreshSeconds(), form.isScreen(), null, form.background());
        d.setStyleConfig(json.write(form.styleConfig()));
        d.setLayout(json.write(form.layout()));
        // 分组归属仅 create 从表单取值（update 不 set groupId：编辑器 payload 不携带，
        // 全量 set 会静默清空分组；移动归属统一走 moveToGroup 专用端点）
        d.setGroupId(blankToId(form.groupId()));
        dashboardMapper.insert(d);
        resourceAclService.grant(TenantContext.requireTenantId(), "DASHBOARD", d.getId(),
                d.getUid(), null, ResourceAcl.VISIBILITY_ORG);
        return toVO(d);
    }

    @Transactional
    public DashboardVO update(Long id, DashboardForm form) {
        require(id);
        dashboardMapper.update(null, Wrappers.<Dashboard>lambdaUpdate()
                .eq(Dashboard::getId, id)
                .set(Dashboard::getName, form.name())
                .set(Dashboard::getDescription, form.description())
                .set(Dashboard::getTheme, blankTo(form.theme(), "light"))
                .set(Dashboard::getCols, form.cols() == null ? DEFAULT_COLS : form.cols())
                .set(Dashboard::getRefreshSeconds, form.refreshSeconds() == null ? 0 : form.refreshSeconds())
                .set(Dashboard::getIsScreen, form.isScreen() == null ? 0 : form.isScreen())
                .set(Dashboard::getBackground, blankTo(form.background(), null))
                .set(Dashboard::getStyleConfig, json.write(form.styleConfig()))
                .set(Dashboard::getLayout, json.write(form.layout())));
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        dashboardMapper.deleteById(id);
        resourceAclService.deleteByResource("DASHBOARD", id);
    }

    /**
     * 移动驾驶舱到指定分组（{@code groupId} 空=移出分组）。目标分组须存在（租户隔离表，
     * 跨租户 id 查不到即拒绝）；分组删除前会校验归属清空，故此处不会留下悬挂引用。
     */
    @Transactional
    public DashboardVO moveToGroup(Long id, Long groupId) {
        require(id);
        // 目标分组须存在且属 DASHBOARD 域（共享表按 resourceType 分域，避免把看板挂到指标的分组下）
        if (groupId != null && !groupService.exists(ResourceGroupService.TYPE_DASHBOARD, groupId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标分组不存在或无权访问: " + groupId);
        }
        dashboardMapper.update(null, Wrappers.<Dashboard>lambdaUpdate()
                .eq(Dashboard::getId, id)
                .set(Dashboard::getGroupId, groupId));
        return get(id);
    }

    /**
     * 批量刷新：解析布局，对每个绑定指标的 widget 重跑 SQL，返回大屏渲染所需的布局 + 数据。
     * 单个 widget 刷新失败降级为占位（{@code result=null}），不影响其余 widget 与整体响应。
     */
    /**
     * 信息图绑定指标（V4.36）：统一收集整屏所需指标（图表槽 + 信息图各 item/group），去重后各刷一次，
     * 再把实时值解析进信息图副本的 item.value（原 {@code layout} JSON 不动，仅改本次响应的分离副本，
     * 规避 load/save 全量回写清空信息图的坑）。单个 widget 刷新失败降级为占位，不影响其余 widget。
     */
    /**
     * 大屏批量刷新（无下钻筛选）：等价于 {@link #getData(Long, DashboardFilter)} 传 null。
     */
    public DashboardDataVO getData(Long id) {
        return getData(id, (DashboardFilter) null);
    }

    /**
     * 大屏批量刷新（下钻筛选 JSON 字符串入口）：解析后委托 {@link #getData(Long, DashboardFilter)}。
     *
     * <p>解析失败/缺 dim 一律按 null（不过滤）处理，不让单条非法筛选拖垮整屏取数。
     */
    public DashboardDataVO getData(Long id, String filterJson) {
        return getData(id, json.readFilter(filterJson));
    }

    /**
     * 大屏批量刷新（支持下钻筛选，V4.44）：布局 + 各 widget 指标实时数据（单 widget 失败降级占位）。
     *
     * <p>{@code filter} 为整屏下钻/联动条件（null=不过滤）：传入后每个绑定指标的 widget 按该维度服务端 SQL 切片
     * （见 {@link MetricService#refresh(Long, DashboardFilter)}）；信息图组件内容驱动、不受维度约束。
     * 自由画布坐标 fx/fy/fw/fh 原样透传前端，供大屏绝对定位渲染。
     */
    public DashboardDataVO getData(Long id, DashboardFilter filter) {
        Dashboard d = require(id);
        List<WidgetLayout> layouts = json.readLayout(d.getLayout());
        // 1) 收集整屏所需指标（图表槽 + 信息图各 item/group），去重后各刷一次
        Set<Long> allMetricIds = new HashSet<>();
        for (WidgetLayout wl : layouts) {
            if (wl.metricId() != null) allMetricIds.add(wl.metricId());
            if (wl.infographic() != null) collectInfographicMetricIds(wl.infographic(), allMetricIds);
        }
        Map<Long, MetricDataVO> refreshed = new HashMap<>();
        for (Long mid : allMetricIds) {
            try {
                refreshed.put(mid, metricService.refresh(mid, filter));
            } catch (RuntimeException e) {
                log.warn("驾驶舱[{}] 指标[{}] 刷新失败（下钻 filter={}），降级占位：{}", id, mid, filter, e.getMessage());
            }
        }
        // 2) 组装每个 widget 的渲染数据（自由画布坐标 fx/fy/fw/fh 透传）
        List<WidgetDataVO> widgets = new ArrayList<>(layouts.size());
        for (WidgetLayout wl : layouts) {
            MetricDataVO data = wl.metricId() != null ? refreshed.get(wl.metricId()) : null;
            // 信息图：把绑定指标的实时值解析进分离副本的 item.value（原 layout 不动）
            Object resolvedInfo = wl.infographic() != null ? resolveInfographic(wl.infographic(), refreshed) : null;
            String title = wl.title() != null ? wl.title() : (data != null ? data.name() : null);
            widgets.add(new WidgetDataVO(wl.i(), wl.metricId(), title, wl.x(), wl.y(), wl.w(), wl.h(),
                    data != null ? data.chartSpec() : null,
                    wl.chartStyle(),
                    wl.style(),
                    wl.kind(),
                    resolvedInfo,
                    wl.fx(), wl.fy(), wl.fw(), wl.fh(),
                    data != null ? data.result() : null, wl.image(), wl.list()));
        }
        return new DashboardDataVO(toVO(d), widgets);
    }

    /** 遍历信息图配置，收集所有 item/group 绑定的 metricId（去重交给调用方 Set）。 */
    @SuppressWarnings("unchecked")
    private void collectInfographicMetricIds(Object info, Set<Long> ids) {
        if (!(info instanceof Map)) return;
        Map<String, Object> root = (Map<String, Object>) info;
        collectMetricIdsFromList(root.get("items"), ids);
        Object groups = root.get("groups");
        if (groups instanceof List) {
            for (Object g : (List<Object>) groups) {
                if (g instanceof Map) {
                    Map<String, Object> gm = (Map<String, Object>) g;
                    addMetricId(gm, ids);
                    collectMetricIdsFromList(gm.get("children"), ids);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectMetricIdsFromList(Object list, Set<Long> ids) {
        if (list instanceof List) {
            for (Object it : (List<Object>) list) {
                if (it instanceof Map) addMetricId((Map<String, Object>) it, ids);
            }
        }
    }

    private void addMetricId(Map<String, Object> m, Set<Long> ids) {
        Object mid = m.get("metricId");
        if (mid instanceof Number) ids.add(((Number) mid).longValue());
    }

    /**
     * 把信息图各 item/group 绑定指标的实时值解析进分离副本的 item.value（原 layout 不动）。
     * 未绑定 / 刷新失败 / 不可转有限数 → 保留手填 value（降级）。
     */
    @SuppressWarnings("unchecked")
    private Object resolveInfographic(Object info, Map<Long, MetricDataVO> refreshed) {
        if (!(info instanceof Map)) return info;
        Map<String, Object> root = (Map<String, Object>) info;
        fillItemsFromList(root.get("items"), refreshed);
        Object groups = root.get("groups");
        if (groups instanceof List) {
            for (Object g : (List<Object>) groups) {
                if (g instanceof Map) {
                    Map<String, Object> gm = (Map<String, Object>) g;
                    fillItemValue(gm, refreshed);
                    fillItemsFromList(gm.get("children"), refreshed);
                }
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private void fillItemsFromList(Object list, Map<Long, MetricDataVO> refreshed) {
        if (list instanceof List) {
            for (Object it : (List<Object>) list) {
                if (it instanceof Map) fillItemValue((Map<String, Object>) it, refreshed);
            }
        }
    }

    private void fillItemValue(Map<String, Object> m, Map<Long, MetricDataVO> refreshed) {
        Object mid = m.get("metricId");
        if (!(mid instanceof Number)) return;                       // 未绑定 → 保留手填
        MetricDataVO mv = refreshed.get(((Number) mid).longValue());
        if (mv == null || mv.result() == null) return;             // 刷新失败 → 回落手填
        QueryResult r = mv.result();
        // 字段绑定（valueField / labelField / descField）：绑定指标后按列名分别取数，优先级高于 extract。
        Object vf = m.get("valueField");
        Object lf = m.get("labelField");
        Object df = m.get("descField");
        String valueField = vf == null ? "" : vf.toString();
        String labelField = lf == null ? "" : lf.toString();
        String descField = df == null ? "" : df.toString();
        boolean bound = false;
        if (!valueField.isEmpty()) {
            Object v = toNumber(columnValue(r, valueField));
            if (v != null) { m.put("value", v); bound = true; }    // 数值字段：转有限数填入 value
        }
        if (!labelField.isEmpty()) {
            Object v = columnValue(r, labelField);
            if (v != null) { m.put("label", String.valueOf(v)); bound = true; }
        }
        if (!descField.isEmpty()) {
            Object v = columnValue(r, descField);
            if (v != null) { m.put("desc", String.valueOf(v)); bound = true; }
        }
        if (!bound) {
            // 未指定字段绑定 → 回落旧 extract 规则（向后兼容 V4.36 之前数据）
            Object v = extractInfographicValue(r, (String) m.get("extract"));
            if (v != null) m.put("value", v);
        }
    }

    /** 取首行指定列的值；列不存在或无行返回 null（上层回落手填）。 */
    private Object columnValue(QueryResult r, String col) {
        List<String> cols = r.columns();
        if (cols == null) return null;
        int idx = cols.indexOf(col);
        if (idx < 0) return null;
        List<List<Object>> rows = r.rows();
        if (rows == null || rows.isEmpty()) return null;
        return rows.get(0).get(idx);
    }

    /** 按取值规则从 QueryResult 取单值；取不到返回 null（上层回落手填）。 */
    private Object extractInfographicValue(QueryResult r, String extract) {
        List<List<Object>> rows = r.rows();
        if (rows == null || rows.isEmpty()) return null;
        List<String> cols = r.columns();
        String rule = (extract == null || extract.isEmpty()) ? "firstCell" : extract;
        if (rule.startsWith("column:")) {
            int idx = cols == null ? -1 : cols.indexOf(rule.substring("column:".length()));
            return idx >= 0 ? toNumber(rows.get(0).get(idx)) : null;
        }
        if (rule.equals("scalar")) {
            return (r.rowCount() == 1 && cols != null && cols.size() == 1) ? toNumber(rows.get(0).get(0)) : null;
        }
        if (rule.equals("rowSum") || rule.startsWith("rowSum:")) {
            int idx = 0;
            if (rule.length() > "rowSum:".length()) {
                String col = rule.substring("rowSum:".length());
                idx = cols == null ? -1 : cols.indexOf(col);
            }
            if (idx < 0) idx = 0;
            double sum = 0;
            for (List<Object> row : rows) {
                Number n = toNumber(row.get(idx));
                if (n != null) sum += n.doubleValue();
            }
            return sum;
        }
        // firstCell（默认）
        return toNumber(rows.get(0).get(0));
    }

    private static Number toNumber(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return (Number) o;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 大屏公开分享（问题2：实时公开链接）====================

    /** 查询某大屏当前的分享状态（供分享对话框预填）。 */
    public DashboardShareVO shareStatus(Long id) {
        Dashboard d = require(id);
        boolean enabled = !isBlank(d.getShareToken());
        return new DashboardShareVO(enabled, d.getShareToken(),
                enabled ? buildShareUrl(d.getShareToken()) : null,
                d.getShareExpireAt(), d.getSharePassword() != null);
    }

    /**
     * 开启/更新/关闭大屏公开分享。
     *
     * <p>开启时生成（或复用既有）能力令牌 {@code share_token}，可选设访问密码（BCrypt）与有效期；关闭时清空
     * 令牌/密码/有效期，旧链接立即失效。令牌即访问凭证，公开访问免认证。
     */
    @Transactional
    public DashboardShareVO configShare(Long id, DashboardShareForm form) {
        Dashboard d = require(id);
        if (!Boolean.TRUE.equals(form.enabled())) {
            dashboardMapper.update(null, Wrappers.<Dashboard>lambdaUpdate()
                    .eq(Dashboard::getId, id)
                    .set(Dashboard::getShareToken, null)
                    .set(Dashboard::getSharePassword, null)
                    .set(Dashboard::getShareExpireAt, null));
            log.info("关闭大屏分享: id={}", id);
            return new DashboardShareVO(false, null, null, null, false);
        }
        String token = isBlank(d.getShareToken())
                ? UUID.randomUUID().toString().replace("-", "")
                : d.getShareToken();
        String pwdHash = isBlank(form.password()) ? null : passwordEncoder.encode(form.password());
        LocalDateTime expireAt = form.expireHours() == null
                ? null : LocalDateTime.now().plusHours(form.expireHours());
        dashboardMapper.update(null, Wrappers.<Dashboard>lambdaUpdate()
                .eq(Dashboard::getId, id)
                .set(Dashboard::getShareToken, token)
                .set(Dashboard::getSharePassword, pwdHash)
                .set(Dashboard::getShareExpireAt, expireAt));
        log.info("开启大屏分享: id={}, expire={}, hasPassword={}", id, expireAt, pwdHash != null);
        return new DashboardShareVO(true, token, buildShareUrl(token), expireAt, pwdHash != null);
    }

    /**
     * 公开访问（凭 token，免认证）：校验有效期/密码后，在<b>该大屏所属租户上下文</b>内实时重跑各 widget 指标 SQL。
     *
     * <p>与结果分享（快照）不同，大屏分享是<b>实时</b>的——故需临时设置 {@link TenantContext} 令租户拦截器与数据源
     * 连接池按大屏所属租户解析；planType 不影响只读取数路径（连接池仅按 config 的 tenant:ds 建键），取数毕立即清理，杜绝串租户。
     */
    public DashboardAccessResponse accessByToken(String token, String password, String filterJson) {
        if (isBlank(token)) {
            return DashboardAccessResponse.notFound();
        }
        Dashboard d = dashboardMapper.selectByShareToken(token);   // 关闭租户注入，按 token 直查
        if (d == null) {
            return DashboardAccessResponse.notFound();
        }
        if (d.getShareExpireAt() != null && LocalDateTime.now().isAfter(d.getShareExpireAt())) {
            return DashboardAccessResponse.expired();
        }
        if (d.getSharePassword() != null) {
            if (isBlank(password)) {
                return DashboardAccessResponse.passwordRequired();
            }
            if (!passwordEncoder.matches(password, d.getSharePassword())) {
                return DashboardAccessResponse.passwordWrong();
            }
        }
        TenantContext.set(d.getTenantId(), null);   // 公开访问无 JWT；仅取实时数据，用毕即清
        // 设置"匿名同租户"上下文：空角色 → 字段级脱敏对公开查看者生效（敏感列置 ***），
        // 与问数/指标链路一致的脱敏语义；用毕即清，避免串线程。
        AccessContextHolder.set(AccessContext.builder().tenantId(d.getTenantId()).build());
        try {
            return DashboardAccessResponse.ok(getData(d.getId(), json.readFilter(filterJson)));
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    private String buildShareUrl(String token) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/share/screen/" + token;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 一键自动布局：按图表类型定尺寸，在 12 列网格逐行填充生成驾驶舱。 */
    @Transactional
    public DashboardVO autoDesign(AutoDesignForm form) {
        List<Metric> metrics = loadMetricsOrdered(form.metricIds());
        if (metrics.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选指标不存在或无权访问");
        }
        List<WidgetLayout> layouts = packGrid(metrics);
        Dashboard d = newDashboard(form.name(), form.description(), form.theme(),
                DEFAULT_COLS, form.refreshSeconds(), form.isScreen(), null, null);
        d.setLayout(json.writeLayout(layouts));
        dashboardMapper.insert(d);
        resourceAclService.grant(TenantContext.requireTenantId(), "DASHBOARD", d.getId(),
                d.getUid(), null, ResourceAcl.VISIBILITY_ORG);
        return toVO(d);
    }

    /** 从内置模板实例化：拷贝骨架布局，把已存指标按顺序绑定到槽位（不足留空、超出忽略）。 */
    @Transactional
    public DashboardVO fromTemplate(FromTemplateForm form) {
        DashboardTemplate tpl = templateMapper.selectOne(Wrappers.<DashboardTemplate>lambdaQuery()
                .eq(DashboardTemplate::getCode, form.templateCode()));
        if (tpl == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驾驶舱模板不存在: " + form.templateCode());
        }
        List<WidgetLayout> skeleton = json.readLayout(tpl.getLayout());
        List<Long> metricIds = form.metricIds() == null ? List.of() : form.metricIds();
        List<WidgetLayout> bound = new ArrayList<>(skeleton.size());
        for (int idx = 0; idx < skeleton.size(); idx++) {
            WidgetLayout slot = skeleton.get(idx);
            Long mid = idx < metricIds.size() ? metricIds.get(idx) : null;
            String i = slot.i() != null ? slot.i() : ("slot" + idx);
            bound.add(new WidgetLayout(i, mid, slot.x(), slot.y(), slot.w(), slot.h(), slot.title(),
                    slot.chartStyle(), slot.style(), slot.kind(), slot.infographic(), null, null, null, null, null, slot.list()));
        }
        Dashboard d = newDashboard(form.name(), form.description(),
                blankTo(form.theme(), blankTo(tpl.getTheme(), "dark")),
                DEFAULT_COLS, form.refreshSeconds(), form.isScreen(), tpl.getCode(), null);
        d.setLayout(json.writeLayout(bound));
        dashboardMapper.insert(d);
        resourceAclService.grant(TenantContext.requireTenantId(), "DASHBOARD", d.getId(),
                d.getUid(), null, ResourceAcl.VISIBILITY_ORG);
        return toVO(d);
    }

    // ---------- 内部工具 ----------

    /** 逐行填充 12 列网格：按图表类型定尺寸，超出列宽换行，行高取本行最大高度。 */
    private List<WidgetLayout> packGrid(List<Metric> metrics) {
        List<WidgetLayout> layouts = new ArrayList<>(metrics.size());
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int seq = 0;
        for (Metric m : metrics) {
            int[] wh = sizeFor(m.getChartSpec());
            int w = wh[0];
            int h = wh[1];
            if (x + w > DEFAULT_COLS) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            layouts.add(new WidgetLayout("w" + (seq++), m.getId(), x, y, w, h, m.getName(), null, null, null, null, null, null, null, null, null, null));
            x += w;
            rowHeight = Math.max(rowHeight, h);
        }
        return layouts;
    }

    /** 图表类型 → 网格尺寸 [w,h]：PIE 4×4、TABLE 6×3、BAR/LINE 及其他 6×4。 */
    private int[] sizeFor(String chartSpecJson) {
        return switch (chartTypeOf(chartSpecJson)) {
            case "PIE" -> new int[]{4, 4};
            case "TABLE" -> new int[]{6, 3};
            default -> new int[]{6, 4};
        };
    }

    private String chartTypeOf(String chartSpecJson) {
        Object spec = json.read(chartSpecJson);
        if (spec instanceof Map<?, ?> map) {
            Object t = map.get("type");
            if (t != null) {
                return t.toString();
            }
        }
        return "TABLE";
    }

    /** 按给定顺序加载本租户可见指标（selectBatchIds 自动限本租户），丢弃不存在/越权 id。 */
    private List<Metric> loadMetricsOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Metric> byId = new LinkedHashMap<>();
        for (Metric m : metricMapper.selectBatchIds(ids)) {
            byId.put(m.getId(), m);
        }
        List<Metric> ordered = new ArrayList<>();
        for (Long id : ids) {
            Metric m = byId.get(id);
            if (m != null) {
                ordered.add(m);
            }
        }
        return ordered;
    }

    private Dashboard newDashboard(String name, String description, String theme,
                                   Integer cols, Integer refreshSeconds, Integer isScreen,
                                   String templateCode, String background) {
        Dashboard d = new Dashboard();
        d.setUid(safeUid());
        d.setName(name);
        d.setDescription(description);
        d.setTemplateCode(templateCode);
        d.setTheme(blankTo(theme, "light"));
        d.setCols(cols == null ? DEFAULT_COLS : cols);
        d.setRefreshSeconds(refreshSeconds == null ? 0 : refreshSeconds);
        d.setIsScreen(isScreen == null ? 0 : isScreen);
        d.setBackground(blankTo(background, null));   // 每屏背景覆盖主题 screenBg（空=跟随主题）
        return d;
    }

    private Dashboard require(Long id) {
        Dashboard d = dashboardMapper.selectById(id);
        if (d == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驾驶舱不存在或无权访问: " + id);
        }
        return d;
    }

    private DashboardVO toVO(Dashboard d) {
        return new DashboardVO(d.getId(), d.getName(), d.getDescription(), d.getTemplateCode(), d.getGroupId(),
                d.getTheme(),
                d.getCols(), d.getRefreshSeconds(), d.getIsScreen(), d.getBackground(), json.read(d.getStyleConfig()),
                json.read(d.getLayout()),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    /** 空值/0 归一为 null（前端「未分组」可能传 0 或 null，统一落库为 NULL）。 */
    private Long blankToId(Long id) {
        return id == null || id <= 0 ? null : id;
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private long safeUid() {
        Long uid = UserContext.userId();
        return uid == null ? 0L : uid;
    }
}
