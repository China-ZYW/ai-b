/**
 * AI 布局协作 composable（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）：
 * 确定性算法「AI 自适应布局」与大模型「AI 智能设计」两条路径共用布局准备（preparePlan），
 * 算法基线同时作为发给大模型的参考解。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import { planAdaptiveLayout, type AdaptiveInput, type AdaptivePlan } from '@/utils/smartLayout'
import type { AiLayoutFeature, AiLayoutSlot } from '@/types'
import { DEFAULT_COLS } from './editorTypes'
import type { EditorCore } from './useEditorCore'

export function useEditorAi(core: EditorCore) {
  const { form, layout, metaMap, widgetData, metrics, bgInput, fetchWidget } = core

  /* ---------------- 布局准备（两条 AI 路径共用） ---------------- */

  /**
   * 补齐尚未加载的 widget 数据，并算出确定性算法基线布局。
   *
   * 「AI 自适应布局」与「AI 智能设计（大模型）」共用此步，保证两条路径的数据基础一致：
   * 算法基线同时也是发给大模型的参考解（模型在其基础上做语义优化，而非从零生成）。
   */
  async function preparePlan(): Promise<{
    plan: AdaptivePlan
    cols: number
    /** 未取到真实数据、只能按图表类型估算的组件数。 */
    estimated: number
  } | null> {
    const cols = form.cols || DEFAULT_COLS
    if (!layout.value.length) {
      ElMessage.warning('画布为空，请先拖入指标')
      return null
    }
    // 自适应依赖真实数据形态：先补齐尚未加载的 widget 数据（已加载的不会重复请求）
    const pending = layout.value
      .map((it) => ({ key: String(it.i), metricId: metaMap[String(it.i)]?.metricId ?? null }))
      .filter((p) => p.metricId != null && !widgetData[p.key]?.result && !widgetData[p.key]?.loading)
    if (pending.length) {
      await Promise.all(pending.map((p) => fetchWidget(p.key, p.metricId)))
    }

    const inputs: AdaptiveInput[] = layout.value.map((it) => {
      const key = String(it.i)
      const meta = metaMap[key]
      const m = metrics.value.find((mm) => mm.id === meta?.metricId)
      const st = widgetData[key]
      return {
        i: it.i,
        name: meta?.title || m?.name || '',
        chartSpec: st?.chartSpec ?? m?.chartSpec ?? null,
        result: st?.result ?? null
      }
    })

    // 大屏模式走行数预算（8 行）：网格按视口等比分行，行数越多每行越扁。
    // ★基线也要守预算，否则 AI 一键生成失败/关闭回退到算法布局时同样会排出压扁的布局。
    const plan = planAdaptiveLayout(inputs, cols, { screen: form.isScreen === 1 })
    return { plan, cols, estimated: inputs.filter((it) => !it.result).length }
  }

  /** 把一组槽位应用到画布（缺槽位的组件保持原位不动）。 */
  function applySlots(
    slots: { i: string | number; x: number; y: number; w: number; h: number }[]
  ): void {
    const byKey = new Map(slots.map((s) => [String(s.i), s]))
    layout.value = layout.value.map((it) => {
      const s = byKey.get(String(it.i))
      return s ? { ...it, x: s.x, y: s.y, w: s.w, h: s.h } : it
    })
  }

  /* ---------------- AI 自适应布局（确定性算法，零延迟） ---------------- */
  async function autoLayout(): Promise<void> {
    const r = await preparePlan()
    if (!r) return
    applySlots(r.plan.slots)
    const tail = r.estimated ? `（${r.estimated} 个未取到数据，按图表类型估算）` : ''
    ElMessage.success({ message: `AI 自适应布局：${r.plan.summary}${tail}`, duration: 3200 })
  }

  /* ---------------- AI 智能设计（大模型自定义布局 + 样式填充） ---------------- */
  const aiDesigning = ref(false)

  /**
   * 把各组件的数据形态摘要与算法基线发给后端，由大模型产出语义分组更合理的布局 +
   * 整套视觉样式。大模型不可用 / 输出非法时由后端回退，此处继续应用算法基线。
   */
  async function aiSmartDesign(): Promise<void> {
    let intent = ''
    try {
      const box = await ElMessageBox.prompt(
        '可描述你想要的风格或取舍，例如「科技感深色，把 GMV 趋势放在最显眼位置」。留空则由模型自行判断。',
        'AI 智能设计',
        {
          confirmButtonText: '开始设计',
          cancelButtonText: '取消',
          inputPlaceholder: '（可留空）',
          inputType: 'textarea',
          inputValidator: (v: string) => (v && v.length > 200 ? '请控制在 200 字以内' : true)
        }
      )
      intent = (box.value ?? '').trim()
    } catch {
      return // 用户取消
    }

    aiDesigning.value = true
    try {
      const prepared = await preparePlan()
      if (!prepared) return
      const { plan, cols } = prepared

      // 数据形态摘要：只传统计量与标题，绝不外发明细数据行
      const items: AiLayoutFeature[] = plan.slots.map((s) => {
        const f = s.features
        const key = String(s.i)
        const m = metrics.value.find((mm) => mm.id === metaMap[key]?.metricId)
        return {
          i: key,
          name: metaMap[key]?.title || m?.name || '',
          chartType: f.type,
          roleHint: s.role,
          rowCount: f.rowCount,
          colCount: f.colCount,
          categories: f.categories,
          series: f.series,
          maxLabel: f.maxLabel,
          single: f.single
        }
      })
      const baseline: AiLayoutSlot[] = plan.slots.map((s) => ({
        i: String(s.i),
        x: s.x,
        y: s.y,
        w: s.w,
        h: s.h,
        reason: s.reason
      }))

      const res = await dashboardApi.aiLayout({
        cols,
        isScreen: form.isScreen ?? 0,
        intent: intent || null,
        items,
        baseline
      })

      const usedLlm = res.source === 'llm' && (res.slots?.length ?? 0) > 0
      applySlots(usedLlm ? res.slots : plan.slots)
      if (usedLlm) {
        applyAiStyle(res.screenBg, res.style)
        ElMessage.success({
          message: `AI 智能设计（大模型）：${res.summary || res.message || '已应用'}`,
          duration: 3600
        })
      } else {
        ElMessage.warning({
          message: `大模型未给出可用方案，已应用确定性算法布局：${res.message ?? ''}`,
          duration: 4200
        })
      }
    } catch {
      ElMessage.error('AI 智能设计失败，请稍后重试')
    } finally {
      aiDesigning.value = false
    }
  }

  /** 应用大模型给出的整屏背景与大屏级样式覆盖（键与值已由后端白名单校验）。 */
  function applyAiStyle(screenBg?: string | null, style?: Record<string, unknown> | null): void {
    if (screenBg && screenBg.trim()) {
      form.background = screenBg.trim()
      bgInput.value = form.background
    }
    if (!style) return
    const s = form.styleConfig
    const pick = (k: string): string | null => {
      const v = style[k]
      return typeof v === 'string' && v.trim() ? v.trim() : null
    }
    const keys = [
      'headerBg',
      'cardBg',
      'cardBorder',
      'accent',
      'textPrimary',
      'textSecondary',
      'textMuted',
      'fontFamily'
    ] as const
    for (const k of keys) {
      const v = pick(k)
      if (v) s[k] = v
    }
    const fs = style.fontScale
    if (typeof fs === 'number' && fs >= 0.8 && fs <= 1.4) s.fontScale = String(fs)
    const cr = style.cardRadius
    if (typeof cr === 'number' && cr >= 0 && cr <= 24) s.cardRadius = String(cr)
    // 「无边框科技风」：后端白名单已校验为布尔；false 时隐藏卡片外边框（含标题横线与四角角标）。
    // ★必须与后端 AiLayoutAdvisor 允许输出的键同步，否则模型给了值也不会生效。
    const scb = style.showCardBorder
    if (typeof scb === 'boolean') s.showCardBorder = scb
  }

  return { preparePlan, applySlots, autoLayout, aiSmartDesign, applyAiStyle, aiDesigning }
}

export type EditorAi = ReturnType<typeof useEditorAi>
