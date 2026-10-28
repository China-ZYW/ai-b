import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { BizError, R } from '@/types'
import { ErrCode } from '@/types'
import { clearAuth, getToken } from '@/utils/storage'

/**
 * 允许调用方显式声明「匿名请求」：刻意不带 Authorization 头。
 * 用于公开预览/分享等本就允许匿名访问的接口——避免本地存在的 stale/revoked 令牌被
 * JwtAuthFilter 判为过期（回 401），进而触发前端拦截器整页跳转登录页。
 */
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 匿名请求：刻意不带 Authorization 头（公开预览/分享等）。 */
    anonymous?: boolean
    /**
     * 跳过全局「登录态失效 → 整页跳登录」处理。用于 login/register 自身：
     * 它们失败时后端也回 401/code=40001，但不应触发整页跳转（否则页面刷新、错误提示被吞）。
     * 由调用方在 catch 中自行提示。
     */
    skipAuthRedirect?: boolean
    /**
     * 关闭「同请求在途去重」（写请求默认开启）。见 {@link withWriteDedupe}。
     *
     * <p>仅在**确实存在"同一份内容连续提交两次是用户真实意图"**时才关闭——例如问数：用户就是要
     * 把同一个问题问两遍，此时静默合并会吞掉一次提问，并让历史/配额与用户实际动作对不上。
     */
    dedupe?: boolean
  }
}

/**
 * 全局 axios 实例：baseURL=/api（开发期由 Vite 代理转发到后端，规避后端禁用 CORS 的跨域问题）。
 *
 * 拦截器职责：
 * - 请求：自动注入 Authorization: Bearer <token>
 * - 响应：解包统一响应 R<T>，code!=0 视为业务失败（ElMessage 提示 + reject BizError）；
 *         HTTP 401 / code=40001 → 清除登录态并跳转登录页。
 */
const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000
})

/** 避免同一时刻多个 401 触发多次跳转/多条提示。 */
let redirecting = false

/**
 * 登录态失效统一处理：清 auth + 整页跳转登录（确保所有 store 被重置）。
 * @param code 后端业务码：40311=权限已变更/会话已吊销（提示重新登录），其余按登录过期处理。
 */
function handleUnauthorized(code?: number): void {
  clearAuth()
  if (redirecting) return
  redirecting = true
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  const revoked = code === ErrCode.SESSION_REVOKED
  ElMessage.error(revoked ? '您的权限已变更，请重新登录' : '登录已过期，请重新登录')
  // 用整页跳转，确保所有 store 状态被重置
  window.location.href = `/login?redirect=${redirect}&reason=${revoked ? 'revoked' : 'expired'}`
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  // anonymous=true 的调用（公开预览/分享等）刻意不带令牌：公开端点（SecurityConfig 已放行 /api/public/**）
  // 本就允许匿名访问，带令牌反而会被 JwtAuthFilter 把 stale/revoked 令牌判过期、回 401 → 整页跳登录。
  if (token && !config.anonymous) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as R<unknown>
    // 非标准包装（极少数直接返回），原样放行
    if (body == null || typeof body !== 'object' || !('code' in body)) {
      return body as never
    }
    if (body.code === ErrCode.SUCCESS) {
      return body.data as never
    }
    if (body.code === ErrCode.UNAUTHORIZED) {
      if (response.config.skipAuthRedirect) {
        // login/register 自身失败：不整页跳转，由调用方在 catch 中提示具体原因
        ElMessage.error(body.message || '认证失败')
      } else {
        handleUnauthorized(body.code)
      }
      return Promise.reject({ code: body.code, message: body.message } as BizError)
    }
    ElMessage.error(body.message || '请求失败')
    return Promise.reject({ code: body.code, message: body.message, traceId: body.traceId } as BizError)
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      if (error?.config?.skipAuthRedirect) {
        // login/register 自身失败：不整页跳转，提示具体原因
        ElMessage.error(error?.response?.data?.message || '认证失败')
      } else {
        // JwtAuthFilter 对已吊销/权限变更会话回 HTTP 401 + body.code（40311 等），据此定制提示
        handleUnauthorized(error?.response?.data?.code as number | undefined)
      }
    } else {
      const msg = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

/* ==================== 写请求在途去重（统一并发护栏） ==================== */

/**
 * 「同请求在途去重」：当**完全相同**的写请求（同 method + 同 url + 同 body）尚在途中时，
 * 后来的调用不再发第二次，而是复用前一次返回的 Promise。
 *
 * <p><b>为什么放在这一层</b>：写操作分散在 30 余个页面/组件里，每个页面各自用
 * {@code submitting} / {@code togglingId} 自保，漏一处就漏一处；而且"连点保存"这类问题
 * 的根因是**同一个动作被提交了两次**，判定它需要的信息（method+url+body）只有请求层才齐备。
 * 在请求层做一次，等于给所有写接口加了同一道兜底，页面里的护栏退化为"提示与视觉反馈"。
 *
 * <p><b>只去重"在途"的请求</b>：请求一旦返回（无论成功失败）即从表中摘除。因此
 * "改完存、再改再存"、"失败后重试"都不受影响——只有**尚未返回时又发了一次一模一样的**才会被合并，
 * 这在实践中只可能是重复点击/回车重复触发。
 *
 * <p><b>刻意不去重的情况</b>：
 * <ul>
 *   <li>上传类（{@code FormData} / {@code Blob} / {@code ArrayBuffer}）：{@code JSON.stringify}
 *       对它们恒为 {@code "{}"}，按 body 比对会把两次不同的上传误判成同一个请求而丢掉文件；</li>
 *   <li>调用方显式声明 {@code dedupe: false}（见 {@link AxiosRequestConfig#dedupe}）；</li>
 *   <li>body 无法序列化（存在循环引用等）：无法可靠比对，宁可放行。</li>
 * </ul>
 *
 * <p><b>已知取舍</b>：被合并的两次调用共享同一个响应对象，若调用方拿到后直接改动它，两侧会看到
 * 同一份数据。现有调用方均只读取字段（列表刷新走各页面的 {@code load()}），故不构成问题；
 * 后续若有"拿到响应后原地改写"的写法，需按需在调用处关掉去重。
 */

/** 参与去重的 HTTP 方法（写方法）。 */
const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete'])

/** 在途写请求表：key = method + url + 序列化后的 body，value = 该次请求的 Promise。 */
const inflightWrites = new Map<string, Promise<unknown>>()

/** 去重命中次数（诊断/验证用：连续两次相同提交应只让真实请求数 +1）。 */
let writeDedupeHits = 0

/** 当前在途写请求数（诊断/验证用）。 */
export function inflightWriteCount(): number {
  return inflightWrites.size
}

/** 去重命中累计次数（诊断/验证用）。 */
export function writeDedupeHitCount(): number {
  return writeDedupeHits
}

/** 判断该次调用是否满足去重条件（见 {@link withWriteDedupe} 的说明）。 */
function dedupeKeyOf(method: string, url: string, data: unknown): string | null {
  if (!WRITE_METHODS.has(method)) return null
  if (data == null) return `${method.toUpperCase()} ${url} `
  // 上传类不参与去重：它们的 JSON 序列化恒为 "{}"，会把不同文件误判为同一请求
  if (typeof FormData !== 'undefined' && data instanceof FormData) return null
  if (typeof Blob !== 'undefined' && data instanceof Blob) return null
  if (typeof ArrayBuffer !== 'undefined' && data instanceof ArrayBuffer) return null
  let body: string
  try {
    body = JSON.stringify(data)
  } catch {
    return null
  }
  if (body === undefined) return null
  return `${method.toUpperCase()} ${url} ${body}`
}

/** 按需执行去重：命中在途同请求则复用其 Promise，否则真实发出并在返回后摘除。 */
function withWriteDedupe<T>(
  method: string,
  url: string,
  data: unknown,
  config: AxiosRequestConfig | undefined,
  send: () => Promise<unknown>
): Promise<T> {
  if (config?.dedupe === false) return send() as Promise<T>
  const key = dedupeKeyOf(method, url, data)
  if (key == null) return send() as Promise<T>
  const inflight = inflightWrites.get(key)
  if (inflight) {
    writeDedupeHits += 1
    return inflight as Promise<T>
  }
  const pending = send().finally(() => {
    inflightWrites.delete(key)
  })
  inflightWrites.set(key, pending)
  return pending as Promise<T>
}

/** 类型化 GET（返回已解包的 data）。 */
export function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.get(url, config) as unknown as Promise<T>
}

/** 类型化 POST（默认参与「同请求在途去重」，见 {@link withWriteDedupe}）。 */
export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return withWriteDedupe<T>('post', url, data, config, () => http.post(url, data, config))
}

/** 类型化 PUT（默认参与「同请求在途去重」）。 */
export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return withWriteDedupe<T>('put', url, data, config, () => http.put(url, data, config))
}

/** 类型化 DELETE（默认参与「同请求在途去重」，DELETE 无 body 故按 url 判定）。 */
export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return withWriteDedupe<T>('delete', url, undefined, config, () => http.delete(url, config))
}

export default http
