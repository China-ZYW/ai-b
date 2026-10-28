/**
 * 轻量 Markdown → HTML 渲染（用户手册用）。
 *
 * 覆盖手册实际用到的子集：
 *  - 标题(# ~ ######，注入顺序化锚点 id 供目录定位)
 *  - 无序/有序列表、引用(>)、分隔线(---)
 *  - GFM 表格(|)
 *  - 行内 **加粗** 与 `代码`
 *  - 围栏代码块(``` )：普通代码块渲染为 <pre><code>；```mermaid 流程图渲染为
 *    <div class="mermaid">，交由 mermaid.js 在 DOM 插入后异步渲染（见 utils/mermaid.ts）。
 *
 * 安全策略（双重保险）：
 *  1) 先按原始文本抽取所有围栏代码块（保留未转义内容，mermaid/代码语义不能被转义破坏）；
 *  2) 再对剩余正文做 HTML 转义，即便正文含 <script> 也只会当作文本显示。
 *  手册正文由超管可信来源编辑，但此处仍默认按不可信处理。
 */

/** 转义 HTML 特殊字符，杜绝注入。 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 行内规则：加粗与行内代码（在已转义的文本上处理）。 */
function inline(text: string): string {
  return text
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

/** 拆分一行表格单元格（去掉首尾竖线后按 | 切分）。 */
function splitRow(row: string): string[] {
  return row
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim())
}

/** 分隔行 → text-align 样式串。 */
function alignAttr(spec?: string): string {
  const s = (spec ?? '').trim()
  const left = s.startsWith(':')
  const right = s.endsWith(':')
  if (left && right) return ' style="text-align:center"'
  if (right) return ' style="text-align:right"'
  if (left) return ' style="text-align:left"'
  return ''
}

/** 渲染一个 GFM 表格块。 */
function renderTable(head: string[], body: string[][], aligns: string[]): string {
  const th = head.map((c, i) => `<th${alignAttr(aligns[i])}>${inline(c)}</th>`).join('')
  const rows = body
    .map((row) => {
      const tds = head
        .map((_, i) => `<td${alignAttr(aligns[i])}>${inline(row[i] ?? '')}</td>`)
        .join('')
      return `<tr>${tds}</tr>`
    })
    .join('')
  return `<table><thead><tr>${th}</tr></thead><tbody>${rows}</tbody></table>`
}

/** 围栏代码块占位符：用私有区字符包裹，避免与正文任何 Markdown 语法冲突。 */
const CB_SENTINEL = ''
const CB_RE = /([0-9]+)/
// 抽取阶段使用的正则：```语言\n内容```（语言可空，内容非贪婪到下一个 ```）
const FENCE_RE = /```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g

/**
 * 渲染整篇 Markdown。逐行扫描并按块聚合（列表/引用/表格需合并连续行）。
 * 返回可直接 v-html 绑定的 HTML 串。
 */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''
  const raw = src.replace(/\r\n/g, '\n')

  // 1) 在 HTML 转义前，抽取所有围栏代码块（保留原始内容，供后续原样回插）。
  const blocks: { lang: string; code: string }[] = []
  let seq = 0
  const text = raw.replace(FENCE_RE, (_full, lang: string, code: string) => {
    blocks.push({ lang: (lang || '').toLowerCase(), code: code.replace(/\n$/, '') })
    return `${CB_SENTINEL}${seq++}${CB_SENTINEL}`
  })

  const lines = escapeHtml(text).split('\n')
  const out: string[] = []

  // 标题层级计数器：用于生成稳定、可读的锚点 id（h-1 / h-1-2 / h-2-3 …），
  // 供手册阅读页的目录(TOC)点击定位、滚动高亮与 #hash 深链使用。
  const counters = [0, 0, 0, 0, 0, 0, 0]

  let listType: 'ul' | 'ol' | null = null
  let paraBuf: string[] = []
  let quoteBuf: string[] = []

  const closeList = (): void => {
    if (listType) {
      out.push(`</${listType}>`)
      listType = null
    }
  }
  const closePara = (): void => {
    if (paraBuf.length) {
      out.push(`<p>${inline(paraBuf.join(' '))}</p>`)
      paraBuf = []
    }
  }
  const closeQuote = (): void => {
    if (quoteBuf.length) {
      out.push(`<blockquote>${inline(quoteBuf.join(' '))}</blockquote>`)
      quoteBuf = []
    }
  }
  const closeAll = (): void => {
    closeList()
    closePara()
    closeQuote()
  }

  for (let idx = 0; idx < lines.length; idx++) {
    const rawLine = lines[idx]
    const line = rawLine.trimEnd()
    const trimmed = line.trim()

    // 围栏代码块占位（已在转义前抽取，内容未转义）
    const cb = CB_RE.exec(trimmed)
    if (cb && trimmed === `${CB_SENTINEL}${cb[1]}${CB_SENTINEL}`) {
      closeAll()
      const b = blocks[Number(cb[1])]
      out.push(
        b.lang === 'mermaid'
          ? `<div class="mermaid">${b.code}</div>`
          : `<pre class="md-pre"><code>${escapeHtml(b.code)}</code></pre>`
      )
      continue
    }

    if (trimmed === '') {
      closeAll()
      continue
    }
    // 分隔线
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      closeAll()
      out.push('<hr>')
      continue
    }
    // 标题（注入顺序化锚点 id，供 TOC 定位）
    const h = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (h) {
      closeAll()
      const level = h[1].length
      // 当前层级 +1，更深层级归零，得到形如 1 / 1-2 / 2-3 的路径
      counters[level]++
      for (let i = level + 1; i < counters.length; i++) counters[i] = 0
      const id = 'h-' + counters.slice(1, level + 1).join('-')
      out.push(`<h${level} id="${id}">${inline(h[2])}</h${level}>`)
      continue
    }
    // 表格：| 表头 | 表头 | 紧跟 |---|---| 分隔行
    if (trimmed.startsWith('|') && trimmed.endsWith('|') && idx + 1 < lines.length) {
      const divider = lines[idx + 1].trim()
      if (divider.includes('-') && /^\|?[\s:|-]*-[\s:|-]*\|?$/.test(divider)) {
        closeAll()
        const head = splitRow(trimmed)
        const aligns = splitRow(divider)
        idx += 2
        const body: string[][] = []
        while (idx < lines.length) {
          const r = lines[idx].trim()
          if (!r.startsWith('|') || !r.endsWith('|')) break
          body.push(splitRow(r))
          idx++
        }
        idx-- // 抵消 for 循环自身的 idx++
        out.push(renderTable(head, body, aligns))
        continue
      }
    }
    // 引用。注意：正文已先做过 HTML 转义，">" 此时是 "&gt;"，必须按转义后的形式识别，
    // 否则引用块会被当成普通段落渲染成 "&gt; 提示：…"（这是一个隐蔽且长期存在的显示缺陷）。
    if (/^&gt;\s?/.test(trimmed)) {
      closeList()
      closePara()
      quoteBuf.push(trimmed.replace(/^&gt;\s?/, ''))
      continue
    }
    closeQuote()
    // 有序列表
    const ol = /^\d+\.\s+(.*)$/.exec(trimmed)
    if (ol) {
      closePara()
      if (listType !== 'ol') {
        closeList()
        out.push('<ol>')
        listType = 'ol'
      }
      out.push(`<li>${inline(ol[1])}</li>`)
      continue
    }
    // 无序列表
    const ul = /^[-*+]\s+(.*)$/.exec(trimmed)
    if (ul) {
      closePara()
      if (listType !== 'ul') {
        closeList()
        out.push('<ul>')
        listType = 'ul'
      }
      out.push(`<li>${inline(ul[1])}</li>`)
      continue
    }
    // 普通段落文本
    closeList()
    paraBuf.push(trimmed)
  }
  closeAll()
  return out.join('\n')
}
