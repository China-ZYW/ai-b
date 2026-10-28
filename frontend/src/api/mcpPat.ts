import { get, post } from './request'

/** 对外 MCP 的个人访问令牌（PAT）视图：不含任何可还原明文的字段。 */
export interface McpPatVO {
  id: number
  tenantId: number
  uid: number
  name: string | null
  /** 明文前缀，如 aibi_pat_9f3c...；仅用于辨认。 */
  tokenPrefix: string | null
  scopes: string | null
  expiresAt: string | null
  revokedAt: string | null
  lastUsedAt: string | null
  createdAt: string | null
  /** 是否已失效（已吊销或已过期），前端据此标灰。 */
  disabled: boolean
}

/** 创建响应：token 为明文，仅此一次（服务端只存哈希，无法再次展示）。 */
export interface McpPatCreated {
  pat: McpPatVO
  token: string
}

/** 平台后台 MCP 令牌接口（/api/admin/mcp-pats，admin:mcp:read / admin:mcp:write）。 */
export const mcpPatApi = {
  /** 某组织的令牌列表。 */
  list: (tenantId: number) => get<McpPatVO[]>('/admin/mcp-pats', { params: { tenantId } }),
  /**
   * 签发一枚令牌。**刻意关闭在途去重**：同名连点两次是"想要两枚令牌"的真实意图，
   * 被去重吞掉一次会让用户以为签发失败。
   */
  create: (data: { tenantId: number; uid: number; name?: string; days?: number | null }) =>
    post<McpPatCreated>('/admin/mcp-pats', data, { dedupe: false }),
  /** 吊销（立即生效）。 */
  revoke: (id: number) => post<void>(`/admin/mcp-pats/${id}/revoke`)
}
