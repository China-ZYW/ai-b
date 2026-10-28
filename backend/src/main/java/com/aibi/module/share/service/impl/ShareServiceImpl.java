package com.aibi.module.share.service.impl;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.share.dto.ShareAccessResponse;
import com.aibi.module.share.dto.ShareRequest;
import com.aibi.module.share.dto.ShareVO;
import com.aibi.module.share.dto.SharedView;
import com.aibi.module.share.entity.ShareLink;
import com.aibi.module.share.mapper.ShareLinkMapper;
import com.aibi.module.dashboard.entity.Dashboard;
import com.aibi.module.dashboard.mapper.DashboardMapper;
import com.aibi.module.share.service.ShareService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分享链接服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p><b>快照而非实时</b>：创建时把结果截断到 {@code snapshot-max-rows} 行、连同图表推荐序列化为 JSON 落库；
 * 公开访问只读快照，绝不回查业务库。<b>能力令牌</b>：{@code token} 即访问凭证，公开访问用关闭租户注入的
 * {@link ShareLinkMapper#selectByToken} 按 token 直查；创建者侧（建/列/删）则由拦截器自动按 {@code tenant_id} 隔离。
 */
@Service
public class ShareServiceImpl extends ServiceImpl<ShareLinkMapper, ShareLink> implements ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareServiceImpl.class);

    /**
     * {@code share_link.chart_spec} 为 VARCHAR(2048)（按字符计）：写入前留余量校验。
     *
     * <p>图表展示样式（轴名/单位/数值格式）入 JSON 后长度不再可控，而 MySQL 严格模式下超长会直接报错、
     * 非严格模式下静默截断——两者都会让分享链接打不开（读取时解析失败）。图表属于呈现细节，
     * 宁可降级为明细表也不能让整条分享失败，故超限时丢弃 chart_spec（与驾驶舱列展示名的处理一致）。
     */
    private static final int CHART_SPEC_MAX = 2000;

    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final DashboardMapper dashboardMapper;
    private final int snapshotMaxRows;
    private final String publicBaseUrl;

    public ShareServiceImpl(ObjectMapper objectMapper,
                            PasswordEncoder passwordEncoder,
                            DashboardMapper dashboardMapper,
                            @Value("${aibi.share.snapshot-max-rows:500}") int snapshotMaxRows,
                            @Value("${aibi.share.public-base-url:}") String publicBaseUrl) {
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.dashboardMapper = dashboardMapper;
        this.snapshotMaxRows = snapshotMaxRows;
        this.publicBaseUrl = publicBaseUrl;
    }

    // ==================== 创建者侧（需认证，租户+用户隔离）====================

    @Override
    public ShareVO createShare(Long uid, PlanType plan, ShareRequest request) {
        String token = UUID.randomUUID().toString().replace("-", "");
        QueryResult capped = capRows(request.result());
        boolean watermark = Boolean.TRUE.equals(request.watermark()) || plan == PlanType.FREE;
        LocalDateTime expireAt = request.expireHours() == null
                ? null : LocalDateTime.now().plusHours(request.expireHours());
        String pwdHash = isBlank(request.password()) ? null : passwordEncoder.encode(request.password());

        ShareLink entity = new ShareLink();
        entity.setUid(uid);
        entity.setToken(token);
        entity.setDatasourceId(request.datasourceId());
        entity.setQuestion(request.question());
        entity.setSqlText(request.sql());
        entity.setResultSnapshot(writeJson(capped));
        entity.setChartSpec(writeChartSpec(request.chart()));
        entity.setPassword(pwdHash);
        entity.setExpireAt(expireAt);
        entity.setWatermark(watermark ? 1 : 0);
        entity.setViewCount(0);
        save(entity);   // 租户拦截器自动补 tenant_id；IdType.AUTO 回填自增主键

        long id = entity.getId() == null ? 0L : entity.getId();
        log.info("创建分享: uid={}, id={}, watermark={}, expire={}", uid, id, watermark, expireAt);
        return new ShareVO(id, token, buildUrl(token), request.question(), expireAt, watermark,
                pwdHash != null, 0, LocalDateTime.now(), "metric");
    }

    @Override
    public List<ShareVO> listShares(Long uid) {
        // 仅取列表所需列，排除 result_snapshot / chart_spec 等大字段，避免无谓 IO
        List<ShareLink> rows = lambdaQuery()
                .select(ShareLink::getId, ShareLink::getToken, ShareLink::getQuestion, ShareLink::getExpireAt,
                        ShareLink::getWatermark, ShareLink::getPassword, ShareLink::getViewCount, ShareLink::getCreatedAt)
                .eq(ShareLink::getUid, uid)
                .orderByDesc(ShareLink::getId)
                .list();
        List<ShareVO> vos = new ArrayList<>(rows.size());
        for (ShareLink e : rows) {
            vos.add(new ShareVO(e.getId(), e.getToken(), buildUrl(e.getToken()), e.getQuestion(),
                    e.getExpireAt(), isWatermark(e.getWatermark()), e.getPassword() != null,
                    e.getViewCount(), e.getCreatedAt(), "metric"));
        }
        // 聚合「已开启分享的驾驶舱」：分享令牌存于 dashboard 表自身字段（share_token），不经 share_link，
        // 故分享管理列表原本看不到。这里按当前用户 + 租户（拦截器注入）聚合进来，type=dashboard 让前端区分渲染/取消。
        List<Dashboard> sharedDashboards = dashboardMapper.selectList(Wrappers.<Dashboard>lambdaQuery()
                .eq(Dashboard::getUid, uid)
                .isNotNull(Dashboard::getShareToken)
                .ne(Dashboard::getShareToken, ""));
        for (Dashboard d : sharedDashboards) {
            vos.add(new ShareVO(d.getId(), d.getShareToken(), dashboardShareUrl(d.getShareToken()),
                    d.getName(), d.getShareExpireAt(), false, d.getSharePassword() != null,
                    0, d.getCreatedAt(), "dashboard"));
        }
        return vos;
    }

    @Override
    public void deleteShare(Long uid, Long id) {
        boolean removed = remove(Wrappers.<ShareLink>lambdaQuery()
                .eq(ShareLink::getId, id)
                .eq(ShareLink::getUid, uid));
        if (!removed) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "分享不存在或无权访问");
        }
    }

    // ==================== 公开访问侧（凭 token，无需认证）====================

    @Override
    public ShareAccessResponse access(String token, String password) {
        if (isBlank(token)) {
            return ShareAccessResponse.notFound();
        }
        ShareLink link = baseMapper.selectByToken(token);   // 关闭租户注入，按 token 直查
        if (link == null) {
            return ShareAccessResponse.notFound();
        }
        if (link.getExpireAt() != null && LocalDateTime.now().isAfter(link.getExpireAt())) {
            return ShareAccessResponse.expired();
        }
        if (link.getPassword() != null) {
            if (isBlank(password)) {
                return ShareAccessResponse.passwordRequired();
            }
            if (!passwordEncoder.matches(password, link.getPassword())) {
                return ShareAccessResponse.passwordWrong();
            }
        }

        baseMapper.incrementViewCount(link.getId());
        QueryResult result = readJson(link.getResultSnapshot(), QueryResult.class);
        ChartSpec chart = readChartSpec(link.getChartSpec());
        SharedView view = new SharedView(link.getQuestion(), link.getSqlText(), result, chart,
                isWatermark(link.getWatermark()), link.getCreatedAt(), link.getExpireAt());
        return ShareAccessResponse.ok(view);
    }

    // ==================== 内部工具 ====================

    /** 截断结果行到快照上限，避免快照过大；列展示名（语义别名）随快照一并冻结，公开页与分享时看到的一致。 */
    private QueryResult capRows(QueryResult r) {
        if (r == null) {
            return null;
        }
        List<List<Object>> rows = r.rows();
        if (rows == null || rows.size() <= snapshotMaxRows) {
            return r;
        }
        List<List<Object>> capped = new ArrayList<>(rows.subList(0, snapshotMaxRows));
        return new QueryResult(r.sql(), r.columns(), r.columnLabels(), capped, capped.size(),
                r.executionTimeMs(), r.enumDicts());
    }

    private String buildUrl(String token) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 注意：前端路由为 /s/:token（通用分享落地页 PublicShareView），故此处路径段必须为 /s/，
        // 与 /share/screen/:token（公开数据大屏，独立路由）区分，否则分享链接命中不到路由而 404。
        return base + "/s/" + token;
    }

    /** 驾驶舱分享链接：路径段为 /share/screen/（公开大屏独立路由），与指标快照分享 /s/ 区分。 */
    private String dashboardShareUrl(String token) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/share/screen/" + token;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分享快照序列化失败: " + e.getMessage());
        }
    }

    /** 图表规格 → {@code chart_spec}：超列宽则丢弃而非报错（降级为明细表，保住分享链接可访问）。 */
    private String writeChartSpec(ChartSpec chart) {
        if (chart == null) {
            return null;
        }
        String json = writeJson(chart);
        if (json != null && json.length() > CHART_SPEC_MAX) {
            log.warn("分享图表规格超长（{} 字符 > {}），已丢弃以降级为明细表，避免落库截断后链接打不开",
                    json.length(), CHART_SPEC_MAX);
            return null;
        }
        return json;
    }

    /**
     * {@code chart_spec} → 图表规格：解析失败返回 null 而不抛异常。
     *
     * <p>与 {@link #readJson} 不同：结果快照解析不出来则分享无意义（必须报错），而图表解析不出来
     * 仅意味着展示形式降级——历史遗留的截断行仍能打开，不至于变成永久不可访问的死链接。
     */
    private ChartSpec readChartSpec(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ChartSpec.class);
        } catch (Exception e) {
            log.warn("分享图表规格解析失败，按明细表展示：{}", e.getMessage());
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分享快照解析失败: " + e.getMessage());
        }
    }

    private boolean isWatermark(Integer flag) {
        return flag != null && flag == 1;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
