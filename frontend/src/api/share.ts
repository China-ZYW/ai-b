import { del, get, post } from './request'
import type { ShareAccessResponse, ShareRequest, ShareVO } from '@/types'

/** 分享管理接口（/api/share，需认证）。 */
export const shareApi = {
  create: (data: ShareRequest) => post<ShareVO>('/share', data),
  list: () => get<ShareVO[]>('/share'),
  remove: (id: number) => del<void>(`/share/${id}`)
}

/** 公开访问接口（/api/public/share，免认证）。 */
export const publicShareApi = {
  access: (token: string, password?: string) =>
    get<ShareAccessResponse>(`/public/share/${token}`, { params: password ? { password } : undefined, anonymous: true })
}
