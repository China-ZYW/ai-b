/**
 * 编辑器布局协作 composable（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）：
 * 网格盒 ⇄ 自由画布百分比坐标互转、自由画布绝对定位样式与拖拽/缩放、大屏槽位模式动态行高。
 */
import { computed } from 'vue'
import { DEFAULT_COLS, MIN_ROW_H, MARGIN, ROW_HEIGHT, clampInt, clampPct, round1 } from './editorTypes'
import type { FreeLayoutItem } from './editorTypes'
import type { EditorCore } from './useEditorCore'

export function useEditorLayout(core: EditorCore) {
  const { form, layout, gridWrap, gridWrapH } = core

  /** 自由画布开关（V4.44）：与 styleConfig.free 双向同步；切换时把整数网格盒 ⇄ 百分比坐标互转。 */
  const freeCanvas = computed<boolean>({
    get: () => !!form.styleConfig.free,
    set: (v: boolean) => toggleFree(v)
  })

  /**
   * 编辑器槽位模式行高：大屏(isScreen=1)按画布内容高度把总行高均分铺满视口，
   * 与预览 ScreenView 的 `repeat(maxRow, minmax(0,1fr))` 视觉一致（WYSIWYG）；
   * 自由画布 / 普通仪表盘保持固定 ROW_HEIGHT，避免非大屏场景被无意义拉伸。
   */
  const editorRowHeight = computed(() => {
    if (freeCanvas.value || form.isScreen !== 1) return ROW_HEIGHT
    const innerH = gridWrapH.value
    if (innerH <= 0) return ROW_HEIGHT
    const maxRow = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 1
    const rh = (innerH - (maxRow - 1) * MARGIN) / maxRow
    return Math.max(MIN_ROW_H, Math.round(rh))
  })

  /**
   * 网格盒 ⇄ 自由画布百分比坐标互转。
   * ★P1 修复：自由画布的 fy/fh 是相对「网格总行高(maxRow)」的百分比，必须用实际 maxRow 换算，
   *   不能用固定 20 行（y*5/h*5）近似——否则 y+h≤10 的大屏会被换算成只占 50% 高度、下半屏空白。
   *   开启时按列/行数换算 fx/fw/fy/fh；关闭时按同一 maxRow 还原并清空百分比字段。
   */
  function toggleFree(v: boolean): void {
    form.styleConfig.free = v
    const cols = form.cols || DEFAULT_COLS
    const maxRow = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 1
    for (const it of layout.value) {
      if (v) {
        it.fx = it.fx ?? round1((it.x / cols) * 100)
        it.fw = it.fw ?? round1((it.w / cols) * 100)
        it.fy = it.fy ?? round1((it.y / maxRow) * 100)
        it.fh = it.fh ?? round1((it.h / maxRow) * 100)
      } else {
        it.x = clampInt((it.fx ?? 0) / 100 * cols, 0, cols - (it.minW ?? 2))
        it.w = clampInt((it.fw ?? 20) / 100 * cols, it.minW ?? 2, cols)
        it.y = clampInt((it.fy ?? 0) / 100 * maxRow, 0, 9999)
        it.h = clampInt((it.fh ?? 20) / 100 * maxRow, it.minH ?? 2, 9999)
        it.fx = it.fy = it.fw = it.fh = null
      }
    }
  }

  /**
   * 自由画布：组件绝对定位样式（fx/fy/fw/fh 百分比）。
   * P6 修复：坐标缺失时不再回退到固定的 20% 小方块（会重叠在左上角），
   * 而是用当前网格盒 x/y/w/h 按列数 / 实际 maxRow 估算，保证落位大致合理。
   */
  function freeStyle(it: FreeLayoutItem): Record<string, string> {
    const cols = form.cols || DEFAULT_COLS
    const maxRow = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 1
    const fx = clampPct(it.fx ?? (it.x / cols) * 100)
    const fw = clampPct(it.fw ?? (it.w / cols) * 100)
    const fy = clampPct(it.fy ?? (it.y / maxRow) * 100)
    const fh = clampPct(it.fh ?? (it.h / maxRow) * 100)
    return {
      position: 'absolute',
      left: `${fx}%`,
      top: `${fy}%`,
      width: `${fw}%`,
      height: `${fh}%`
    }
  }

  /** 自由画布拖拽/缩放：move=拖动整块，resize=右下角手柄缩放；全程按容器尺寸换算成百分比。 */
  function startFreeDrag(it: FreeLayoutItem, e: MouseEvent, mode: 'move' | 'resize'): void {
    if ((e.target as HTMLElement).closest('.gw-tools')) return // 不拦截工具按钮点击
    const rect = gridWrap.value?.getBoundingClientRect()
    if (!rect) return
    const startX = e.clientX
    const startY = e.clientY
    const sx = it.fx ?? 0
    const sy = it.fy ?? 0
    const sw = it.fw ?? 20
    const sh = it.fh ?? 20
    const onMove = (ev: MouseEvent) => {
      const dx = ((ev.clientX - startX) / rect.width) * 100
      const dy = ((ev.clientY - startY) / rect.height) * 100
      if (mode === 'move') {
        it.fx = clampPct(sx + dx)
        it.fy = clampPct(sy + dy)
      } else {
        it.fw = clampPct(sw + dx, 5, 100)
        it.fh = clampPct(sh + dy, 5, 100)
      }
    }
    const onUp = () => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
    }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    e.preventDefault()
  }

  return { freeCanvas, editorRowHeight, toggleFree, freeStyle, startFreeDrag }
}

export type EditorLayout = ReturnType<typeof useEditorLayout>
