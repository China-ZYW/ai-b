import { del, get, post, put } from './request'
import type {
  MemberPasswordForm,
  MemberRoleForm,
  MemberUpdateForm,
  MemberVO,
  OwnerTransferForm
} from '@/types'

/**
 * 组织成员管理接口（/api/org/members）。租户范围由后端从登录上下文强制推导，不接受 tenantId 入参。
 * 权限点 member:*（矩阵 YYNNNN → 仅 OWNER/ADMIN），member:self:leave 全组织角色可用，org:org:transfer 仅 OWNER。
 * 所有护栏（不可操作自己/越级/最后一个 OWNER）在后端强制，前端仅呈现错误。
 */
export const orgMemberApi = {
  /** 本组织在册成员列表（联组织角色）。 */
  list: () => get<MemberVO[]>('/org/members'),
  /** 授予组织角色（OWNER 不可直接授予）。 */
  assignRole: (data: MemberRoleForm) => post<void>('/org/members/roles', data),
  /** 回收组织角色（按 uid + roleCode 路径参数定位）；customRoleId 非空时仅回收该「自定义角色授予」。 */
  revokeRole: (uid: number, roleCode: string, customRoleId?: number | null) =>
    del<void>(`/org/members/roles/${uid}/${encodeURIComponent(roleCode)}`, {
      params: customRoleId == null ? undefined : { customRoleId }
    }),
  /** 更新成员组织内展示资料（昵称、职位）。 */
  update: (uid: number, data: MemberUpdateForm) => put<MemberVO>(`/org/members/${uid}`, data),
  /** 移除成员（置 status=0 + 吊销全部会话 + 释放席位）。 */
  remove: (uid: number) => del<void>(`/org/members/${uid}`),
  /** 重置成员口令（强制其重新登录）。 */
  resetPassword: (uid: number, data: MemberPasswordForm) =>
    post<void>(`/org/members/${uid}/reset-password`, data),
  /** 转让组织所有权（仅 OWNER）。 */
  transfer: (data: OwnerTransferForm) => post<void>('/org/members/transfer', data),
  /** 主动退出组织（OWNER 须先转让所有权）。 */
  leave: () => post<void>('/org/members/leave')
}
