package com.aibi.module.rbac.dto;

import java.util.Set;

/**
 * {@code GET /api/auth/permissions} 的响应体（见《角色权限体系与组织协同设计》6.9 / 11.1）。
 *
 * <p>前端登录/刷新后拉取一次，据此驱动动态菜单（{@code menus}）、按钮显隐（{@code permissions}）、
 * 数据范围提示（{@code dataScope}）与席位余量展示。它是"权限单一事实源"在前端的投影——
 * 前端不再自行按角色推断能力，只消费后端解析好的权限码集合，杜绝前后端两套权限码漂移。
 *
 * @param permissions    当前上下文的有效权限码集合（已按 ASSIST 剥离、按角色并集展开）
 * @param menus          MENU 型权限点对应的前端路由 name 集合（动态菜单数据源）
 * @param dataScope      有效数据范围名（ALL/TENANT/TEAM_AND_CHILD/TEAM/SELF/CUSTOM）
 * @param platformRoles  平台角色码集合（组织身份为空集）
 * @param orgRoles       组织角色码集合
 * @param teamRoles      当前团队生效的团队角色码集合
 * @param currentTeamId  当前团队 id，可空（尚未选定团队）
 * @param teamIds        该身份所属团队 id 集合（请求期零查询路径下可能为空，团队列表由 P2 团队接口补全）
 * @param platform       是否平台域身份（{@code tenantId==0}）
 * @param owner          是否组织所有者
 * @param assist         是否代客协助影子令牌（前端据此展示红色横幅并隐藏全部写按钮）
 * @param seatLimit      席位上限（{@code -1}=不限）；平台身份为 {@code null}
 * @param seatUsed       已用席位；平台身份为 {@code null}
 * @param seatRemaining  剩余席位（{@code seatLimit<0} 时为 {@code null} 表示不限）
 */
public record PermissionsView(
        Set<String> permissions,
        Set<String> menus,
        String dataScope,
        Set<String> platformRoles,
        Set<String> orgRoles,
        Set<String> teamRoles,
        Long currentTeamId,
        Set<Long> teamIds,
        boolean platform,
        boolean owner,
        boolean assist,
        Integer seatLimit,
        Integer seatUsed,
        Integer seatRemaining
) {
}
