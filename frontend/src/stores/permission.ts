import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { permissionApi } from '@/api/permission'
import type { DataScope, PermissionsView } from '@/types'

/**
 * 权限 store（P4，见《角色权限体系与组织协同设计》7.2）。
 *
 * 消费 {@code GET /api/auth/permissions} 的快照，作为全部前端 UI 门禁的唯一判据：
 * 路由守卫、动态菜单（useMenus）、按钮显隐（v-perm / PermGuard / usePerm）都走这里，
 * 禁止各处自行按角色拼判断——权限点是后端解析好的单一事实源。
 *
 * <p><b>与后端实际响应对齐</b>：真实 {@code PermissionsView} 不含文档 §7.1 草案里的
 * {@code identity}/{@code tenant}/{@code teams}/{@code permVersion}，故席位直接取
 * {@code seatUsed/seatLimit/seatRemaining}；团队列表待 P2 团队接口补全，此处仅留 {@code teamIds}。
 * 由于后端未下发版本号，{@code v-perm} 指令改 watch 本地自增的 {@code revision}（每次 load/reset 递增）。
 */
export const usePermissionStore = defineStore('permission', () => {
  const codes = ref<Set<string>>(new Set())
  const menus = ref<Set<string>>(new Set())
  const dataScope = ref<DataScope>('SELF')
  const platformRoles = ref<string[]>([])
  const orgRoles = ref<string[]>([])
  const teamRoles = ref<string[]>([])
  const currentTeamId = ref<number | null>(null)
  const teamIds = ref<number[]>([])
  const seat = ref<{ used: number; limit: number; remaining: number }>({ used: 0, limit: -1, remaining: -1 })
  const platform = ref(false)
  const owner = ref(false)
  const assistMode = ref(false)
  const loaded = ref(false)
  /** 每次 load/reset 自增，供 v-perm 指令 watch 触发重判（后端未下发 permVersion）。 */
  const revision = ref(0)

  /** 单点权限判定：全部 UI 门禁都走这里。 */
  function has(code: string): boolean {
    return codes.value.has(code)
  }
  function hasAny(...list: string[]): boolean {
    return list.some((c) => codes.value.has(c))
  }
  function hasAll(...list: string[]): boolean {
    return list.every((c) => codes.value.has(c))
  }
  function hasRole(...list: string[]): boolean {
    const all = [...platformRoles.value, ...orgRoles.value, ...teamRoles.value]
    return list.some((r) => all.includes(r))
  }

  /** 是否平台域身份（运营/审计/支持/超管均算，用于顶栏平台管理入口显隐）。 */
  const isPlatform = computed(() => platform.value || platformRoles.value.length > 0)
  /** 数据范围能力位：用于“能否看到他人资源”的 UI 提示与列展示。 */
  const canSeeOthers = computed(() =>
    ['ALL', 'TENANT', 'TEAM_AND_CHILD', 'TEAM'].includes(dataScope.value)
  )

  /** 拉取当前上下文权限快照（首屏/刷新/切团队后调用）。失败由调用方按登录态失效处理。 */
  async function load(): Promise<void> {
    const p: PermissionsView = await permissionApi.current()
    codes.value = new Set(p.permissions ?? [])
    menus.value = new Set(p.menus ?? [])
    dataScope.value = (p.dataScope as DataScope) ?? 'SELF'
    platformRoles.value = [...(p.platformRoles ?? [])]
    orgRoles.value = [...(p.orgRoles ?? [])]
    teamRoles.value = [...(p.teamRoles ?? [])]
    currentTeamId.value = p.currentTeamId ?? null
    teamIds.value = [...(p.teamIds ?? [])]
    seat.value = {
      used: p.seatUsed ?? 0,
      limit: p.seatLimit ?? -1,
      remaining: p.seatRemaining ?? -1
    }
    platform.value = !!p.platform
    owner.value = !!p.owner
    assistMode.value = !!p.assist
    loaded.value = true
    revision.value++
  }

  /** 登出 / 切换组织时必须调用：不留半个旧上下文（否则下次登录会复用上一个身份的权限集）。 */
  function reset(): void {
    codes.value = new Set()
    menus.value = new Set()
    dataScope.value = 'SELF'
    platformRoles.value = []
    orgRoles.value = []
    teamRoles.value = []
    currentTeamId.value = null
    teamIds.value = []
    seat.value = { used: 0, limit: -1, remaining: -1 }
    platform.value = false
    owner.value = false
    assistMode.value = false
    loaded.value = false
    revision.value++
  }

  return {
    codes,
    menus,
    dataScope,
    platformRoles,
    orgRoles,
    teamRoles,
    currentTeamId,
    teamIds,
    seat,
    platform,
    owner,
    assistMode,
    loaded,
    revision,
    isPlatform,
    canSeeOthers,
    has,
    hasAny,
    hasAll,
    hasRole,
    load,
    reset
  }
})
