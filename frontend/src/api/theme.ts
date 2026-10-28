import { del, get, post, put } from './request'
import type { ThemeConfig } from '@/types'

/**
 * 主题令牌接口（配置化升级 V2，样式配置化）。
 *
 * - `themeApi`：需认证（/api/theme）——全局预设 + 租户自定义列表、按 code 取、租户自定义 CRUD。
 * - `publicThemeApi`：免认证（/api/public/theme）——公开分享页/大屏取全局内置主题。
 */
export const themeApi = {
  /** 当前租户可见的主题列表（全局预设 + 租户自定义）。 */
  list: () => get<ThemeConfig[]>('/theme/list'),
  /** 按 code 取单个主题（租户优先，回落全局预设）。 */
  get: (code: string) => get<ThemeConfig>(`/theme/${code}`),
  /** 新建租户自定义主题。 */
  create: (data: ThemeConfig) => post<ThemeConfig>('/theme', data),
  /** 更新租户自定义主题。 */
  update: (data: ThemeConfig) => put<ThemeConfig>('/theme', data),
  /** 删除租户自定义主题（内置预设不可删）。 */
  remove: (id: number) => del<void>(`/theme/${id}`)
}

/** 公开主题（免认证）：仅全局内置预设。 */
export const publicThemeApi = {
  list: () => get<ThemeConfig[]>('/public/theme/list', { anonymous: true }),
  get: (code: string) => get<ThemeConfig>(`/public/theme/${code}`, { anonymous: true })
}
