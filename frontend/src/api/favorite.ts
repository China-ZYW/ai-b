import { del, get, post } from './request'
import type { FavoriteRequest, FavoriteVO } from '@/types'

/** 问数收藏接口（/api/favorite）。 */
export const favoriteApi = {
  list: () => get<FavoriteVO[]>('/favorite'),
  /** 新增收藏，返回其 id。 */
  create: (data: FavoriteRequest) => post<number>('/favorite', data),
  remove: (id: number) => del<void>(`/favorite/${id}`)
}
