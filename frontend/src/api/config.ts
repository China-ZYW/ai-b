import { del, get, put } from './request'
import type { EffectiveConfig, SysConfig } from '@/types'

/**
 * 配置中心接口（配置化升级 V2）。
 *
 * - `configApi`：租户侧（/api/config）——合并视图供前端消费，TENANT scope 覆盖的增改删。
 * - `adminConfigApi`：超管侧（/api/admin/config）——GLOBAL scope 平台默认值管理。
 */
export const configApi = {
  /** 当前租户合并视图：category → key → value（前端结果面板/图表消费）。 */
  effective: () => get<EffectiveConfig>('/config/effective'),
  /**
   * 合并视图的说明字典：category → key → description（GLOBAL 兜底、TENANT 非空覆盖）。
   * 供配置页「当前生效值」表展示中文说明；与 effective 分开端点是为了不动其扁平结构
   * （stores/config.ts 启动时消费，改形状会波及全部展示配置消费方）。
   */
  descriptions: () => get<Record<string, Record<string, string>>>('/config/descriptions'),
  /**
   * 平台管控键清单，元素形如 `"CATEGORY:configKey"`：配置页据此把对应项标为「平台管控」并禁用编辑。
   * 关闭管控后租户覆盖原样复活（管控期间不删租户数据）。
   */
  managed: () => get<string[]>('/config/managed'),
  /** 本租户某分类的配置覆盖行（管理页编辑用）。 */
  rows: (category: string) => get<SysConfig[]>('/config/rows', { params: { category } }),
  /**
   * upsert 本租户配置覆盖；若该键已被平台管控，后端以 40301 拒绝。
   * 返回 `null` 表示**内容无变化未写入**（版本号不变），前端应提示"未发生变化"而非"已保存"。
   */
  upsert: (data: { category: string; configKey: string; valueJson?: string | null; description?: string | null }) =>
    put<SysConfig | null>('/config', data),
  /** 删除本租户配置覆盖（恢复平台默认）。 */
  remove: (id: number) => del<void>(`/config/${id}`)
}

/** 超管配置中心（/api/admin/config，需 SUPER_ADMIN）：管理 GLOBAL 平台默认值。 */
export const adminConfigApi = {
  rows: (category: string) => get<SysConfig[]>('/admin/config/rows', { params: { category } }),
  /**
   * upsert 平台默认配置。`managed: true` 把该键升格为**平台管控**：所有租户的覆盖立即失效且不可再写；
   * `false` 解除管控（租户覆盖复活）；`null`/省略 = 不改动现有标记。
   * 返回 `null` 表示**内容无变化未写入**（版本号不变）。
   */
  upsert: (data: {
    category: string
    configKey: string
    valueJson?: string | null
    description?: string | null
    managed?: boolean | null
  }) => put<SysConfig | null>('/admin/config', data),
  remove: (id: number) => del<void>(`/admin/config/${id}`)
}
