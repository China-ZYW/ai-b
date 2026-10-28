package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.UserContext;
import com.aibi.module.dashboard.dto.DashboardGroupForm;
import com.aibi.module.dashboard.dto.DashboardGroupVO;
import com.aibi.module.dashboard.entity.Dashboard;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.entity.ResourceGroup;
import com.aibi.module.dashboard.mapper.DashboardMapper;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.dashboard.mapper.ResourceGroupMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资源分组服务：<b>驾驶舱与指标库共用</b>的多层级目录树 CRUD 与归属校验。
 *
 * <p><b>分域</b>：所有方法都带 {@code resourceType}（{@link #TYPE_DASHBOARD} / {@link #TYPE_METRIC}），
 * 查询与校验一律带上它——否则指标的接口能改到驾驶舱的分组（跨域越权）。
 *
 * <p><b>隔离</b>：{@code resource_group}、{@code dashboard}、{@code metric} 均为租户隔离表，
 * {@code tenantId} 由租户拦截器自动注入/追加，本服务不做手工租户过滤。
 *
 * <p><b>成环防护</b>：移动分组时沿 {@code newParentId} 向根逐级回溯，遇自身即拒绝——
 * 否则该子树将从树上游离，列表永远渲染不出来。
 *
 * <p><b>删除语义</b>：非空分组（有子分组或有资源归属）拒绝删除，提示先移出成员。
 * 不做级联搬移/置空——静默改动用户编排的目录结构比多点一次手工操作更伤信任。
 */
@Service
public class ResourceGroupService {

    private static final Logger log = LoggerFactory.getLogger(ResourceGroupService.class);

    /** 驾驶舱分组域。 */
    public static final String TYPE_DASHBOARD = "DASHBOARD";
    /** 指标库分组域。 */
    public static final String TYPE_METRIC = "METRIC";

    private final ResourceGroupMapper groupMapper;
    private final DashboardMapper dashboardMapper;
    private final MetricMapper metricMapper;

    public ResourceGroupService(ResourceGroupMapper groupMapper,
                                DashboardMapper dashboardMapper,
                                MetricMapper metricMapper) {
        this.groupMapper = groupMapper;
        this.dashboardMapper = dashboardMapper;
        this.metricMapper = metricMapper;
    }

    /** 全量分组（扁平，升序：sort → id），前端组树。 */
    public List<DashboardGroupVO> list(String resourceType) {
        return groupMapper.selectList(Wrappers.<ResourceGroup>lambdaQuery()
                        .eq(ResourceGroup::getResourceType, resourceType)
                        .orderByAsc(ResourceGroup::getSort)
                        .orderByAsc(ResourceGroup::getId))
                .stream().map(this::toVO).toList();
    }

    @Transactional
    public DashboardGroupVO create(String resourceType, DashboardGroupForm form) {
        String name = requireName(form);
        Long parentId = form.parentId();
        if (parentId != null) {
            requireGroup(resourceType, parentId);
        }
        ResourceGroup g = new ResourceGroup();
        g.setUid(safeUid());
        g.setResourceType(resourceType);
        g.setName(name);
        g.setParentId(parentId);
        g.setSort(0);
        groupMapper.insert(g);
        log.info("创建资源分组: type={}, id={}, name={}, parentId={}", resourceType, g.getId(), name, parentId);
        return toVO(g);
    }

    /** 重命名 / 移动分组（可整体搬移子树）。 */
    @Transactional
    public DashboardGroupVO update(String resourceType, Long id, DashboardGroupForm form) {
        ResourceGroup g = requireGroup(resourceType, id);
        String name = requireName(form);
        Long newParentId = form.parentId();
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "不能把分组移动到自身之下");
            }
            requireGroup(resourceType, newParentId);
            ensureNoCycle(id, newParentId);
        }
        g.setName(name);
        g.setParentId(newParentId);
        groupMapper.updateById(g);
        log.info("更新资源分组: type={}, id={}, name={}, parentId={}", resourceType, id, name, newParentId);
        return toVO(g);
    }

    /**
     * 删除空分组：有子分组或仍有资源归属时拒绝（提示先移出），不做级联改动。
     * 归属统计按 {@code resourceType} 走不同的表（驾驶舱 → {@code dashboard}，指标 → {@code metric}）。
     */
    @Transactional
    public void delete(String resourceType, Long id) {
        requireGroup(resourceType, id);
        Long childCount = groupMapper.selectCount(Wrappers.<ResourceGroup>lambdaQuery()
                .eq(ResourceGroup::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该分组下还有 " + childCount + " 个子分组，请先删除或移出");
        }
        Long ownerCount = countMembers(resourceType, id);
        if (ownerCount > 0) {
            String label = TYPE_METRIC.equals(resourceType) ? "个指标" : "个驾驶舱";
            throw new BizException(ErrorCode.PARAM_ERROR, "该分组下还有 " + ownerCount + " " + label + "，请先移动到其他分组");
        }
        groupMapper.deleteById(id);
        log.info("删除资源分组: type={}, id={}", resourceType, id);
    }

    /** 分组是否存在（供"移动归属"端点校验目标分组）。 */
    public boolean exists(String resourceType, Long id) {
        return requireGroupOrNull(resourceType, id) != null;
    }

    // ---------- 内部工具 ----------

    /** 统计挂在该分组下的资源数。 */
    private long countMembers(String resourceType, Long groupId) {
        Long n;
        if (TYPE_METRIC.equals(resourceType)) {
            n = metricMapper.selectCount(Wrappers.<Metric>lambdaQuery().eq(Metric::getGroupId, groupId));
        } else {
            n = dashboardMapper.selectCount(Wrappers.<Dashboard>lambdaQuery().eq(Dashboard::getGroupId, groupId));
        }
        return n == null ? 0L : n;
    }

    /**
     * 成环检测：沿 {@code targetParentId} 向根回溯，途中遇到 {@code movingId} 即说明会把
     * 分组挂到自己（孙）后代之下，形成环。
     */
    private void ensureNoCycle(Long movingId, Long targetParentId) {
        Long cursor = targetParentId;
        while (cursor != null) {
            if (cursor.equals(movingId)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "不能把分组移动到自己的子分组之下");
            }
            ResourceGroup parent = groupMapper.selectById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
    }

    private ResourceGroup requireGroup(String resourceType, Long id) {
        ResourceGroup g = requireGroupOrNull(resourceType, id);
        if (g == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分组不存在或无权访问: " + id);
        }
        return g;
    }

    private ResourceGroup requireGroupOrNull(String resourceType, Long id) {
        if (id == null) {
            return null;
        }
        return groupMapper.selectOne(Wrappers.<ResourceGroup>lambdaQuery()
                .eq(ResourceGroup::getId, id)
                .eq(ResourceGroup::getResourceType, resourceType));
    }

    private String requireName(DashboardGroupForm form) {
        String name = form == null || form.name() == null ? "" : form.name().trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分组名称不能为空");
        }
        if (name.length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分组名称过长（最多 64 字）");
        }
        return name;
    }

    private long safeUid() {
        Long uid = UserContext.userId();
        return uid == null ? 0L : uid;
    }

    private DashboardGroupVO toVO(ResourceGroup g) {
        return new DashboardGroupVO(g.getId(), g.getName(), g.getParentId(), g.getSort(), g.getCreatedAt());
    }
}
