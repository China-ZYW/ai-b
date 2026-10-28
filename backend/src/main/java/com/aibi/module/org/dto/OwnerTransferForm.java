package com.aibi.module.org.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 转让组织所有权表单（{@code org:org:transfer}，矩阵 YNNNNN → 仅 OWNER）。
 *
 * <p>{@code targetUid} 必须是本组织在册成员（{@code app_user.status=1}）。转让后：目标升为 OWNER 并回写
 * {@code tenant.owner_uid}，原 OWNER 降为 ADMIN（保留管理权、不再是所有者）。属组织级授权变更，
 * 服务层 {@code perm_version++} + {@code evictTenant} 使全组织旧令牌失效重登，并记 {@code OWNER_TRANSFER} 审计。
 *
 * <p>这是「OWNER 不可被移除/不可主动退出/不可被回收角色」诸护栏的唯一解锁路径——先转让再操作。
 */
public record OwnerTransferForm(
        @NotNull(message = "目标成员不能为空") Long targetUid) {
}
