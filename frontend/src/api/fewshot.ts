import { del, get, post, put } from './request'
import type { FewShotExample } from '@/types'

/**
 * 问数样例库接口（配置化升级 V2，/api/fewshot）：curated "问法 → SQL" 样例 CRUD。
 * 样例以最高权重合并进少样本，是问数准确率的可运营抓手。租户隔离由拦截器保证。
 */
export const fewshotApi = {
  /** 样例列表（datasourceId 可选）。 */
  list: (datasourceId?: number) =>
    get<FewShotExample[]>('/fewshot', { params: datasourceId != null ? { datasourceId } : {} }),
  /** 新建样例。 */
  create: (data: FewShotExample) => post<FewShotExample>('/fewshot', data),
  /** 编辑样例。 */
  update: (data: FewShotExample) => put<FewShotExample>('/fewshot', data),
  /** 删除样例。 */
  remove: (id: number) => del<void>(`/fewshot/${id}`)
}
