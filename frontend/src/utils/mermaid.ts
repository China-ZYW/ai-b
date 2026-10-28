/**
 * Mermaid 流程图运行时渲染（用户手册用）。
 *
 * 手册正文的 ```mermaid 代码块经 markdown.ts 渲染为 <div class="mermaid">，
 * 但 mermaid 需要在 DOM 插入后、由 JS 触发渲染——v-html 不会自动执行。
 * 本模块在 DOM 就绪后调用 mermaid.run() 把占位 div 替换为 SVG。
 *
 * 设计要点：
 *  - 动态 import('mermaid')：仅当手册确实含 .mermaid 节点时才加载该体积较大的依赖，
 *    避免污染首屏与主包。
 *  - startOnLoad:false：手动控制渲染时机，配合 Vue 的 v-html 更新节奏。
 *  - securityLevel:'strict'：禁用流程图内的原始 HTML/脚本，满足手册内容不可信假设。
 *  - 失败兜底：单图渲染异常不影响其余内容，控制台记录便于排查。
 */

type MermaidApi = (typeof import('mermaid'))['default']

let mermaidMod: MermaidApi | null = null
let initializing: Promise<MermaidApi> | null = null

async function ensureMermaid(): Promise<MermaidApi> {
  if (mermaidMod) return mermaidMod
  if (!initializing) {
    initializing = import('mermaid').then((mod) => {
      const m = mod.default
      m.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'base',
        themeVariables: {
          primaryColor: '#eef3ff',
          primaryTextColor: '#1f2d3d',
          primaryBorderColor: '#002fa7',
          lineColor: '#5b6b8c',
          secondaryColor: '#f2f6ff',
          tertiaryColor: '#ffffff',
          fontFamily:
            '-apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
        },
        flowchart: { htmlLabels: true, curve: 'basis' }
      })
      mermaidMod = m
      return m
    })
  }
  return initializing
}

/**
 * 渲染指定根节点下的全部 mermaid 图。
 * @param root 查找起点；省略则全局 document。
 */
export async function renderDiagrams(root?: HTMLElement | Document | null): Promise<void> {
  const scope: Document | HTMLElement = root ?? document
  const nodes = Array.from(scope.querySelectorAll<HTMLElement>('.mermaid'))
  if (!nodes.length) return
  try {
    const m = await ensureMermaid()
    await m.run({ nodes })
  } catch (e) {
    // 单图失败不应阻断整页；保留原始文本便于人工核对
    console.error('[mermaid] 渲染失败', e)
  }
}
