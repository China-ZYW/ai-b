import { get, post, put } from './request'
import type {
  AuthResponse,
  LoginRequest,
  MyTenantVO,
  RegisterRequest,
  UpdateProfileRequest,
  UserVO
} from '@/types'

/** 认证与用户接口（/api/auth）。login/register 免认证，其余需 Bearer 令牌。 */
export const authApi = {
  register: (data: RegisterRequest) => post<AuthResponse>('/auth/register', data, { skipAuthRedirect: true }),
  login: (data: LoginRequest) => post<AuthResponse>('/auth/login', data, { skipAuthRedirect: true }),
  /** 无状态登出：后端仅返回成功，前端清除本地令牌即可。 */
  logout: () => post<void>('/auth/logout'),
  me: () => get<UserVO>('/auth/me'),
  updateProfile: (data: UpdateProfileRequest) => put<UserVO>('/auth/me', data),
  /** 当前自然人身份可切换的组织列表（组织切换器数据源）。单组织/未归并身份返回空。 */
  myTenants: () => get<MyTenantVO[]>('/auth/me/tenants'),
  /** 切换到当前身份的另一个成员组织，返回新令牌（前端换存后刷新权限快照）。 */
  switchTenant: (tenantId: number) => post<AuthResponse>('/auth/switch-tenant', { tenantId })
}
