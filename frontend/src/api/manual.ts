import { get, post, put } from './request'
import type { ManualSaveForm, ManualVO, PublishedManualVO } from '@/types'

/**
 * 用户手册接口。
 *
 * - `manualApi`：用户侧（/api/manual）——阅读已发布手册。
 * - `adminManualApi`：超管侧（/api/admin/manual，需 SUPER_ADMIN）——编辑草稿、发布。
 */
export const manualApi = {
  /** 已发布手册（从未发布时 data 为 null）。 */
  published: () => get<PublishedManualVO | null>('/manual')
}

/** 超管用户手册管理（/api/admin/manual）。 */
export const adminManualApi = {
  /** 编辑视图：草稿 + 已发布快照。 */
  get: () => get<ManualVO>('/admin/manual'),
  /** 保存草稿（不影响线上发布版）。 */
  saveDraft: (data: ManualSaveForm) => put<ManualVO>('/admin/manual', data),
  /** 发布：草稿拷贝为发布版，version+1。 */
  publish: () => post<ManualVO>('/admin/manual/publish')
}
