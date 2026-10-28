package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.AdminOverviewTrendVO;
import com.aibi.module.admin.dto.AdminOverviewVO;
import com.aibi.module.admin.mapper.AdminStatsMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管 · 平台概览接口。受 {@code /api/admin/** → hasRole('SUPER_ADMIN')} 保护。
 *
 * <p>跨租户聚合统计（租户/账号/数据源/大模型配置/驾驶舱数量、今日与累计问数、失败数），
 * 供超管后台首页概览卡片使用；{@code /trend} 提供近 14 天时间序列与分布数据供图表区使用。
 * 数据来自 {@link AdminStatsMapper}（全部 {@code @InterceptorIgnore}）。
 */
@RestController
@RequestMapping("/api/admin/overview")
public class AdminOverviewController {

    private final AdminStatsMapper statsMapper;

    public AdminOverviewController(AdminStatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    @GetMapping
    @RequiresPermission(value = "admin:overview:read", platformOnly = true)
    public R<AdminOverviewVO> overview() {
        AdminOverviewVO vo = new AdminOverviewVO(
                statsMapper.countTenants(),
                statsMapper.countUsers(),
                statsMapper.countDataSources(),
                statsMapper.countLlmConfigs(),
                statsMapper.countDashboards(),
                statsMapper.countTodayQueries(),
                statsMapper.countTotalQueries(),
                statsMapper.countFailedQueries());
        return R.ok(vo);
    }

    /** 套餐枚举 → 展示名（与官网/租户管理文案一致；未知枚举原样展示）。 */
    private static final Map<String, String> PLAN_LABELS = Map.of(
            "FREE", "体验版",
            "PRO", "团队版",
            "ENTERPRISE", "企业版");

    /**
     * 近 14 天运营趋势 + 分布数据（图表区）。
     *
     * <p>日期序列在服务端补零对齐（含今天共 14 天、升序），前端拿到即可直接渲染。
     */
    @GetMapping("/trend")
    @RequiresPermission(value = "admin:overview:read", platformOnly = true)
    public R<AdminOverviewTrendVO> trend() {
        List<AdminOverviewTrendVO.QueryTrendPoint> queryTrend = buildQueryTrend();
        List<AdminOverviewTrendVO.SignupTrendPoint> signupTrend = buildSignupTrend();
        List<AdminOverviewTrendVO.NameValue> planDistribution = buildPlanDistribution();
        List<AdminOverviewTrendVO.NameValue> topTenants = buildNameValue(statsMapper.topTenantsRaw());
        return R.ok(new AdminOverviewTrendVO(queryTrend, signupTrend, planDistribution, topTenants));
    }

    /** 近 14 天问数/失败趋势：按日期补零对齐（缺数据的日期计 0，保证 x 轴连续）。 */
    private List<AdminOverviewTrendVO.QueryTrendPoint> buildQueryTrend() {
        Map<String, long[]> byDate = indexByDate(statsMapper.queryTrendRaw(), "total", "failed");
        List<AdminOverviewTrendVO.QueryTrendPoint> list = new ArrayList<>(14);
        for (LocalDate day : last14Days()) {
            long[] v = byDate.getOrDefault(day.toString(), new long[]{0, 0});
            list.add(new AdminOverviewTrendVO.QueryTrendPoint(day.toString(), v[0], v[1]));
        }
        return list;
    }

    /** 近 14 天新增账号/租户趋势：同样补零对齐。 */
    private List<AdminOverviewTrendVO.SignupTrendPoint> buildSignupTrend() {
        Map<String, long[]> users = indexByDate(statsMapper.userTrendRaw(), "users", null);
        Map<String, long[]> tenants = indexByDate(statsMapper.tenantTrendRaw(), "tenants", null);
        List<AdminOverviewTrendVO.SignupTrendPoint> list = new ArrayList<>(14);
        for (LocalDate day : last14Days()) {
            long u = users.getOrDefault(day.toString(), new long[]{0, 0})[0];
            long t = tenants.getOrDefault(day.toString(), new long[]{0, 0})[0];
            list.add(new AdminOverviewTrendVO.SignupTrendPoint(day.toString(), u, t));
        }
        return list;
    }

    /** 套餐分布（体验版/团队版/企业版 + 数量）。 */
    private List<AdminOverviewTrendVO.NameValue> buildPlanDistribution() {
        List<AdminOverviewTrendVO.NameValue> list = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.planDistributionRaw()) {
            String raw = String.valueOf(row.get("name"));
            list.add(new AdminOverviewTrendVO.NameValue(
                    PLAN_LABELS.getOrDefault(raw, raw), asLong(row.get("value"))));
        }
        return list;
    }

    /** 活跃租户 TOP8（按累计问数）。 */
    private List<AdminOverviewTrendVO.NameValue> buildNameValue(List<Map<String, Object>> rows) {
        List<AdminOverviewTrendVO.NameValue> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(new AdminOverviewTrendVO.NameValue(
                    String.valueOf(row.get("name")), asLong(row.get("value"))));
        }
        return list;
    }

    /** 最近 14 天日期（含今天，升序）。 */
    private List<LocalDate> last14Days() {
        LocalDate today = LocalDate.now();
        List<LocalDate> days = new ArrayList<>(14);
        for (int i = 13; i >= 0; i--) {
            days.add(today.minusDays(i));
        }
        return days;
    }

    /**
     * 把 Map 行集按日期键收进 {@code date -> [主值, 次值]} 索引。
     * {@code secondCol} 为 null 时次值恒 0；数值列统一经 {@link #asLong} 收敛类型。
     */
    private Map<String, long[]> indexByDate(List<Map<String, Object>> rows,
                                            String mainCol, String secondCol) {
        Map<String, long[]> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            // DATE() 在驱动侧返回 java.sql.Date，toString 即 yyyy-MM-dd
            String date = String.valueOf(row.get("d"));
            long main = asLong(row.get(mainCol));
            long second = secondCol == null ? 0 : asLong(row.get(secondCol));
            out.put(date, new long[]{main, second});
        }
        return out;
    }

    /** 数值收敛：COUNT→Long、SUM(CASE)→BigDecimal，统一取 longValue。 */
    private long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
