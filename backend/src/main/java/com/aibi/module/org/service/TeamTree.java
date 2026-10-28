package com.aibi.module.org.service;

import com.aibi.module.org.entity.Team;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队树的内存快照：把一次查回的 {@code List<Team>} 组织成可 O(1) 展开子树/回溯祖先的结构。
 *
 * <p><b>为什么不在 SQL 里展开</b>：MySQL 8 的 {@code WITH RECURSIVE} 虽可用，但租户拦截器要解析每条 SQL，
 * 而项目锁定的 jsqlparser 4.6（版本必须随 MyBatis-Plus 3.5.5，勿单独升级）对递归 CTE 支持不完整，
 * 解析失败会直接抛异常。改为"一次取回本组织全部团队 + Java 侧 BFS"，既规避解析风险，
 * 也把展开 N 层的 N 次查询压成 1 次，且结果可按 {@code tenant_id} 缓存。
 *
 * <p>本类<b>不可变</b>且所有集合均为不可变视图，可安全跨线程共享（缓存命中后并发只读）。
 * 团队数据变更时应重新构建快照，不要试图原地修改。
 *
 * <p><b>环防护</b>：{@code parent_id} 由业务写入，若出现 A→B→A 的脏数据，朴素 BFS 会死循环。
 * 所有遍历都用 visited 集合兜底，遇到环即停止扩展（该分支的子树不完整，但不会拖垮请求线程）。
 */
public final class TeamTree {

    private static final TeamTree EMPTY = new TeamTree(Map.of(), Map.of(), List.of());

    /** id → 团队。 */
    private final Map<Long, Team> byId;
    /** parentId → 直接子团队 id 列表（顶级团队的 parentId 为 null，不入本表）。 */
    private final Map<Long, List<Long>> childrenOf;
    /** 全部团队（保持传入顺序：parent_id, sort, id）。 */
    private final List<Team> all;

    private TeamTree(Map<Long, Team> byId, Map<Long, List<Long>> childrenOf, List<Team> all) {
        this.byId = byId;
        this.childrenOf = childrenOf;
        this.all = all;
    }

    /** 空树（组织尚无团队时的安全返回值，避免调用方判 null）。 */
    public static TeamTree empty() {
        return EMPTY;
    }

    /**
     * 由团队列表构建快照。
     *
     * <p>{@code parent_id} 指向不存在团队（父团队被硬删）或指向自身的脏数据行，一律<b>按顶级团队处理</b>：
     * 仍然保留在快照里（否则该团队的成员会突然看不到自己的团队资源），但不挂在任何父下，
     * 也就不会参与其他团队的子树展开。{@link #rootIds()} 同样会把它们归为顶级，二者口径一致。
     */
    public static TeamTree of(List<Team> teams) {
        if (teams == null || teams.isEmpty()) {
            return EMPTY;
        }
        Map<Long, Team> byId = new HashMap<>(teams.size() * 2);
        for (Team t : teams) {
            if (t != null && t.getId() != null) {
                byId.put(t.getId(), t);
            }
        }
        Map<Long, List<Long>> children = new HashMap<>();
        for (Team t : byId.values()) {
            Long parentId = t.getParentId();
            // 自环（parent_id = 自身）与指向不存在团队的父级都视为顶级，避免遍历死循环
            if (parentId == null || parentId.equals(t.getId()) || !byId.containsKey(parentId)) {
                continue;
            }
            children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(t.getId());
        }
        children.replaceAll((k, v) -> List.copyOf(v));
        return new TeamTree(Map.copyOf(byId), Map.copyOf(children), List.copyOf(teams));
    }

    public Team get(Long teamId) {
        return teamId == null ? null : byId.get(teamId);
    }

    public boolean contains(Long teamId) {
        return teamId != null && byId.containsKey(teamId);
    }

    public List<Team> all() {
        return all;
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** 直接子团队 id（不含孙级）。 */
    public Set<Long> childIds(Long teamId) {
        List<Long> ids = teamId == null ? null : childrenOf.get(teamId);
        return ids == null || ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    /**
     * 全部后代团队 id（不含自身）；{@code TEAM_AND_CHILD} 数据范围与团队级用量汇总用它。
     *
     * @return 不可变集合；{@code teamId} 不存在时返回空集（而非 null）
     */
    public Set<Long> descendantIds(Long teamId) {
        if (!contains(teamId)) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(teamId);
        // visited 兼作环防护：已入队的节点不再重复扩展
        Set<Long> visited = new HashSet<>();
        visited.add(teamId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long child : childrenOf.getOrDefault(current, List.of())) {
                if (visited.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 自身 + 全部后代团队 id。 */
    public Set<Long> selfAndDescendantIds(Long teamId) {
        if (!contains(teamId)) {
            return Set.of();
        }
        Set<Long> merged = new LinkedHashSet<>();
        merged.add(teamId);
        merged.addAll(descendantIds(teamId));
        return Collections.unmodifiableSet(merged);
    }

    /**
     * 祖先链（由近及远，不含自身）：团队角色继承（{@code role.inherit_to_child=1}）沿此链向上收集。
     *
     * <p>返回顺序是 {@code [父, 祖父, ...]}，便于"就近优先"地决定继承到的团队角色。
     */
    public List<Long> ancestorIds(Long teamId) {
        if (!contains(teamId)) {
            return List.of();
        }
        List<Long> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(teamId);
        Long cursor = byId.get(teamId).getParentId();
        // 上限即团队总数：即使 parent_id 成环也必然在此终止
        while (cursor != null && visited.add(cursor) && chain.size() <= byId.size()) {
            Team parent = byId.get(cursor);
            if (parent == null) {
                break;
            }
            chain.add(cursor);
            cursor = parent.getParentId();
        }
        return List.copyOf(chain);
    }

    /** 顶级团队 id（{@code parent_id} 为空或指向不存在团队），按 {@code sort, id} 顺序。 */
    public List<Long> rootIds() {
        List<Long> roots = new ArrayList<>();
        for (Team t : all) {
            Long parentId = t.getParentId();
            if (parentId == null || parentId.equals(t.getId()) || !byId.containsKey(parentId)) {
                roots.add(t.getId());
            }
        }
        return List.copyOf(roots);
    }
}
