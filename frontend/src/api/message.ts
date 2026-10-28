import { del, get, post } from './request'
import type { NotifyMessageVO, PageResult } from '@/types'

/**
 * 站内信收件箱接口（/api/messages）。
 *
 * 后端按当前登录者身份强制收敛（uid + tenantId 双重兜底），前端不传任何过滤键；
 * 即使租户拦截器漏挡，也只会读到"自己"的信。权限点 message:inbox:read
 * （组织矩阵 YYYYYY → 人人可读"自己的"信），与告警规则 INAPP 渠道配套。
 */
export const messageApi = {
  /** 收件箱列表（支持只看来信 unreadOnly、分页）。后端已按身份收敛。 */
  list: (params: { unreadOnly?: boolean; limit?: number; offset?: number }) =>
    get<PageResult<NotifyMessageVO>>('/messages/list', { params }),
  /** 未读总数（驱动顶栏铃铛角标）。 */
  unreadCount: () => get<number>('/messages/unread-count'),
  /** 标记单条已读。 */
  read: (id: number) => post<void>(`/messages/${id}/read`),
  /** 全部已读。 */
  readAll: () => post<void>('/messages/read-all'),
  /** 删除单条。 */
  remove: (id: number) => del<void>(`/messages/${id}`)
}
