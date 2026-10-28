import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import { quotaApi } from '@/api/quota'
import { usePermissionStore } from '@/stores/permission'
import type { LoginRequest, MyTenantVO, QuotaStatus, RegisterRequest, UpdateProfileRequest, UserVO } from '@/types'
import { clearAuth, getStoredUser, getToken, setStoredUser, setToken } from '@/utils/storage'
import { resolved as resolvedUiTheme, setUiThemeMode, syncUiThemeMode, type UiThemeMode } from '@/theme/appTheme'

/**
 * 用户 store：登录态 / 令牌 / 资料 / 套餐 / 今日配额 / 多组织切换（C 缺口）。
 *
 * <p>多组织：{@code tenants} 为当前自然人可切换的组织列表（仅 >1 时前端显示切换器）；
 * {@code currentTenantId} 派生自 {@code profile.tenantId}，切换组织后随新资料自动同步，
 * 避免双来源不一致。{@link switchOrg} 走「换令牌」而非请求期改租户，与后端一致。
 *
 * 令牌与资料持久化到 localStorage，刷新后自动恢复。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(getToken())
  const profile = ref<UserVO | null>(getStoredUser<UserVO>())
  const quota = ref<QuotaStatus | null>(null)
  /** 当前自然人可切换的组织列表（多组织身份）；空=单组织/未归并身份。 */
  const tenants = ref<MyTenantVO[]>([])

  const isLoggedIn = computed(() => !!token.value)
  /** 当前生效组织 id（派生自资料，切换组织后随 profile 同步）。 */
  const currentTenantId = computed(() => profile.value?.tenantId ?? null)
  /** 是否多组织身份（切换器仅在 >1 个组织时显示）。 */
  const multiOrg = computed(() => tenants.value.length > 1)
  const plan = computed(() => profile.value?.plan ?? null)
  const displayName = computed(() => profile.value?.nickname || profile.value?.username || '用户')
  const unlimited = computed(() => !!quota.value && quota.value.limit < 0)
  /** 平台角色（SUPER_ADMIN / USER）。 */
  const role = computed(() => profile.value?.role ?? null)
  /** 是否平台超级管理员（跨租户管理权限）。 */
  const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')
  /** 登录落地页：超管→平台后台 /admin，普通用户→对话问数 /app/chat。 */
  const homePath = computed(() => (isSuperAdmin.value ? '/admin' : '/app/chat'))
  /**
   * 账号级界面主题偏好（light/dark/system）。权威值在后端 {@code app_user.uiTheme}，
   * 未登录/未返回时回落 'system'（跟随操作系统）。
   */
  const uiTheme = computed<UiThemeMode>(() => {
    const v = profile.value?.uiTheme
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
  })
  /** 当前实际生效的明暗（已把 system 按系统偏好折算），供图标/一次性判断使用。 */
  const isDarkTheme = computed<boolean>(() => resolvedUiTheme.value === 'dark')

  function persist(authToken: string, user: UserVO): void {
    token.value = authToken
    profile.value = user
    setToken(authToken)
    setStoredUser(user)
    // 权威主题值随资料一起到达：以它校正本地镜像（首屏那次是按 localStorage 猜的，可能属于上一个账号）
    syncUiThemeMode(user.uiTheme)
    // 新登录/注册一律清空权限快照，强制路由守卫按新身份重拉（避免未登出直接换账号时复用旧权限集）
    usePermissionStore().reset()
  }

  async function login(payload: LoginRequest): Promise<UserVO> {
    const res = await authApi.login(payload)
    persist(res.token, res.user)
    return res.user
  }

  async function register(payload: RegisterRequest): Promise<UserVO> {
    const res = await authApi.register(payload)
    persist(res.token, res.user)
    return res.user
  }

  /** 拉取当前登录用户资料（应用启动 / 刷新时）。 */
  async function loadMe(): Promise<UserVO | null> {
    if (!token.value) return null
    const user = await authApi.me()
    profile.value = user
    setStoredUser(user)
    syncUiThemeMode(user.uiTheme)
    return user
  }

  /** 拉取当前自然人可切换的组织列表（组织切换器数据源）。单组织/未归并身份返回空。 */
  async function loadTenants(): Promise<void> {
    if (!token.value) return
    try {
      tenants.value = await authApi.myTenants()
    } catch {
      tenants.value = []
    }
  }

  /**
   * 切换到当前身份的另一个成员组织：换存新令牌 + 新资料，并重拉该组织权限快照。
   * 列表随新令牌重新拉取（后端按新上下文标记 current），切换器保持可见；旧组织会话不强制吊销。
   */
  async function switchOrg(tenantId: number): Promise<void> {
    const res = await authApi.switchTenant(tenantId)
    persist(res.token, res.user)
    await loadTenants()
    // 重新拉取新组织的权限快照（persist 已 reset，此处立即 load）
    try {
      await usePermissionStore().load()
    } catch {
      // 权限重载失败由路由守卫兜底
    }
  }

  async function updateProfile(payload: UpdateProfileRequest): Promise<UserVO> {
    const user = await authApi.updateProfile(payload)
    profile.value = user
    setStoredUser(user)
    syncUiThemeMode(user.uiTheme)
    return user
  }

  /**
   * 切换界面主题：写后端（账号级持久化）+ 立即改本地外观。
   *
   * <p>先改本地再写后端：主题切换是"点了要马上看到"的操作，若等后端往返，弱网下会有明显迟滞；
   * 后端写失败也不回滚本地——用户看到的就是他选的，下次登录以库里的旧值为准（并会被
   * {@link loadMe} 校正），不会出现"界面是深色、库里是浅色却没人知道"的静默不一致。
   */
  async function setUiTheme(mode: UiThemeMode): Promise<void> {
    setUiThemeMode(mode)
    try {
      const user = await authApi.updateProfile({ uiTheme: mode })
      profile.value = user
      setStoredUser(user)
      syncUiThemeMode(user.uiTheme)
    } catch {
      // 拦截器已提示；本地保持用户刚选的观感
    }
  }

  /** 刷新今日配额状态（问数面板展示剩余次数）。 */
  async function refreshQuota(): Promise<void> {
    if (!token.value) return
    try {
      quota.value = await quotaApi.status()
    } catch {
      // 配额拉取失败不阻断主流程
    }
  }

  function logout(): void {
    // 无状态登出：通知后端（尽力而为）+ 清除本地
    void authApi.logout().catch(() => undefined)
    token.value = null
    profile.value = null
    quota.value = null
    tenants.value = []
    clearAuth()
    // 权限快照必须同步清空：AppLayout 登出走 SPA 跳转（非整页重载），
    // 否则下个账号登录时守卫见 loaded=true 会复用上一个身份的权限集
    usePermissionStore().reset()
  }

  return {
    token,
    profile,
    quota,
    tenants,
    currentTenantId,
    multiOrg,
    isLoggedIn,
    plan,
    displayName,
    unlimited,
    role,
    isSuperAdmin,
    homePath,
    uiTheme,
    isDarkTheme,
    login,
    register,
    loadMe,
    loadTenants,
    switchOrg,
    updateProfile,
    setUiTheme,
    refreshQuota,
    logout
  }
})
