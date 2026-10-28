import { get, post } from './request'
import type {
  InvitationAcceptResult,
  InvitationCreateForm,
  InvitationCreateResult,
  InvitationPreview,
  InvitationVO
} from '@/types'

/**
 * 组织邀请管理接口（/api/org/invitations）。管理侧 create/list/revoke，权限点 member:invite:*（YYNNNN → 仅 OWNER/ADMIN）。
 * 令牌明文仅创建结果返回一次，服务端只持久化其 SHA-256 哈希。
 */
export const orgInvitationApi = {
  /** 本组织邀请列表（不含令牌哈希）。 */
  list: () => get<InvitationVO[]>('/org/invitations'),
  /** 发起邀请，返回一次性令牌明文与可分发链接。 */
  create: (data: InvitationCreateForm) => post<InvitationCreateResult>('/org/invitations', data),
  /** 撤销邀请（置 REVOKED，令牌随即失效）。 */
  revoke: (id: number) => post<void>(`/org/invitations/${id}/revoke`),
  /** 被邀请者接受邀请（需登录，凭明文令牌加入组织）。 */
  accept: (token: string) =>
    post<InvitationAcceptResult>('/org/invitations/accept', undefined, { params: { token } })
}

/**
 * 公开邀请预览接口（/api/public/invitation，免认证，「加入组织」落地页 /org/join 消费）。
 * 凭明文令牌只读返回邀请概要；接受动作见 orgInvitationApi.accept（需登录后调用）。
 */
export const publicInvitationApi = {
  /**
   * 凭明文令牌预览邀请概要（匿名，只读）。
   * anonymous=true：刻意不带本地令牌，避免 stale 令牌触发 401 → 前端拦截器整页跳登录页
   * （公开落地页本就该让未登录/令牌失效的用户也能看到邀请概要并自行登录/注册）。
   */
  preview: (token: string) => get<InvitationPreview>('/public/invitation/preview', { params: { token }, anonymous: true })
}
