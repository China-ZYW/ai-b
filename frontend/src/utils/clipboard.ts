/**
 * 复制文本到剪贴板。
 *
 * 线上环境经 nginx 走 HTTP（非安全上下文），此时 `navigator.clipboard` 为
 * `undefined`，直接调用 `navigator.clipboard.writeText` 会抛异常导致复制失败。
 * 这里优先使用异步 Clipboard API（HTTPS / localhost 下可用），失败时降级到
 * 临时 textarea + `document.execCommand('copy')`（HTTP 下仍可用），确保分享链接、
 * 邀请链接、SQL 等在任何协议下都能复制。
 *
 * @returns 是否复制成功
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false

  // 1) 优先走标准 Clipboard API（仅在安全上下文可用）
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 落到降级方案
  }

  // 2) 降级：临时 textarea + execCommand（兼容 HTTP）
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-9999px'
    ta.style.left = '-9999px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    ta.setSelectionRange?.(0, text.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
