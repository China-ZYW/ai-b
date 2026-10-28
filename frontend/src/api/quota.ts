import { get } from './request'
import type { QuotaStatus } from '@/types'

/** 配额接口（/api/quota）：展示今日已用 / 剩余次数。 */
export const quotaApi = {
  status: () => get<QuotaStatus>('/quota')
}
