import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { configApi } from '@/api/config'
import { getToken } from '@/utils/storage'
import type { ChartStyle, EffectiveConfig } from '@/types'

/**
 * 配置中心 store（配置化升级 V2）：加载当前租户合并视图（GET /api/config/effective），
 * 暴露结果展示与图表标注相关的类型化读取点，供 ResultPanel / 图表渲染消费。
 *
 * 未认证/加载失败时静默回落代码默认值，渲染不受影响（公开分享页无登录态即走这条路）。
 */
export const useConfigStore = defineStore('config', () => {
  const effective = ref<EffectiveConfig>({})
  const loaded = ref(false)
  let loading: Promise<void> | null = null

  /** 加载合并视图（幂等；force=true 强制刷新）。 */
  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (loading) return loading
    // 无登录态（公开分享页 / 公开大屏）不发请求：/api/config/effective 需认证，401 会被
    // 全局拦截器当成登录过期并整页跳转，把只读快照页顶掉；此时也无从确定租户，直接回落代码默认。
    if (!getToken()) return
    loading = (async () => {
      try {
        effective.value = await configApi.effective()
        loaded.value = true
      } catch {
        // 静默回落代码默认
      } finally {
        loading = null
      }
    })()
    return loading
  }

  function resultVal(key: string): unknown {
    return effective.value?.RESULT?.[key]
  }

  /** 结果面板默认视图：chart / table。 */
  const defaultView = computed<'chart' | 'table'>(() =>
    resultVal('default-view') === 'table' ? 'table' : 'chart'
  )
  /** 结果面板最多展示行数（超出截断提示）。 */
  const maxDisplayRows = computed<number>(() => {
    const n = Number(resultVal('max-display-rows'))
    return Number.isFinite(n) && n > 0 ? n : 500
  })
  /** 数值格式化策略：raw / thousand。 */
  const numberFormat = computed<string>(() => String(resultVal('number-format') ?? 'thousand'))

  function chartVal(key: string): unknown {
    return effective.value?.CHART?.[key]
  }

  /**
   * 图表标注的租户级默认（sys_config CHART 分类）：作为 chart_spec 未带样式时的兜底。
   *
   * <p>只取渲染相关的三项；{@code auto-unit} 是后端单位推断开关（控制是否把推断结果写进
   * chart_spec），前端不消费。未加载时返回空对象，由 resolveChartStyle 回落代码默认。
   */
  const chartDefaults = computed<ChartStyle>(() => {
    const out: ChartStyle = {}
    const fmt = chartVal('value-format')
    const legend = chartVal('legend-position')
    const decimals = Number(chartVal('value-decimals'))
    if (typeof fmt === 'string' && fmt) out.valueFormat = fmt
    if (typeof legend === 'string' && legend) out.legendPosition = legend
    // -1 是后端“自动”的哨兵值，等同于未设置
    if (Number.isFinite(decimals) && decimals >= 0) out.valueDecimals = decimals
    return out
  })

  function attributionVal(key: string): unknown {
    return effective.value?.ATTRIBUTION?.[key]
  }

  /**
   * ATTRIBUTION 分类下的布尔读取：未加载 / 未配置（老库没有该行）/ 值非法时一律按「开」处理。
   *
   * <p>这样"缺配置"与"配了 true"行为一致——升级前后既有体验不变，公开分享页（无登录态、不请求
   * 配置）也不会因为读不到配置而改变表现。
   */
  function attrBool(key: string): boolean {
    const v = attributionVal(key)
    if (typeof v === 'boolean') return v
    if (typeof v === 'string') return v.toLowerCase() !== 'false'
    return true
  }

  /**
   * 归因分析模块总开关（{@code ATTRIBUTION / enabled}，默认开）：关闭后后端
   * {@code AttributionService} 直接拒绝请求，故前端一并隐藏结果面板内的归因入口与调优页
   * 「维度归因」tab——否则会留下"点了必报错"的死入口。
   */
  const attributionEnabled = computed<boolean>(() => attrBool('enabled'))

  /**
   * 问数结果内是否展示「维度归因」面板（{@code ATTRIBUTION / result-panel}，默认开）。
   *
   * <p>与 {@code enabled} 不同：本开关只决定<b>展示位置</b>——关闭后对话结果里不再挂归因入口，
   * 问数调优页的「维度归因」常驻 tab 照常可用（需要时去那里手动分析），归因接口本身也不被禁用。
   */
  const attributionResultPanel = computed<boolean>(() => attrBool('result-panel'))

  /** 通用分类布尔读取：未加载 / 未配置 / 值非法时按 defaultValue 处理（缺省按「开」）。 */
  function catBool(category: string, key: string, defaultValue = true): boolean {
    const cat = effective.value?.[category]
    const v = cat ? cat[key] : undefined
    if (typeof v === 'boolean') return v
    if (typeof v === 'string') return v.toLowerCase() !== 'false'
    return defaultValue
  }

  /** 指标后台自动分析 Skill 总开关（METRIC / auto-analysis，默认开）：关闭后隐藏语义管理页的「指标自动分析」入口。 */
  const metricAutoAnalysisEnabled = computed<boolean>(() => catBool('METRIC', 'auto-analysis'))
  /**
   * 待治理问题沉淀开关（METRIC / issue-distill，默认开）：关闭后面板仅提示开关关闭、
   * 后端返回空报告。与 auto-analysis 同语义（未配置按「开」处理）。
   */
  const issueDistillEnabled = computed<boolean>(() => catBool('METRIC', 'issue-distill'))
  /**
   * 待治理清单「转写」开关（METRIC / issue-distill-convert，默认开）：开则在「待治理问题」tab
   * 每行提供「转指标 / 转少样本」入口；关闭则面板退回纯只读。与 issue-distill 同语义（未配置按「开」）。
   */
  const issueDistillConvertEnabled = computed<boolean>(() => catBool('METRIC', 'issue-distill-convert'))
  /** 待治理问题推送开关（METRIC / issue-push，默认关）：仅供配置页展示，推送是后端 scheduler 行为。 */
  const issuePushEnabled = computed<boolean>(() => catBool('METRIC', 'issue-push', false))
  /**
   * 元数据巡检开关（T11-4，METRIC / meta-patrol，默认开）：开则在「待治理问题」面板展示「元数据巡检」按钮；
   * 关闭后端点拒用、按钮隐藏（未配置按「开」处理，与 issue-distill 同语义）。
   */
  const metaPatrolEnabled = computed<boolean>(() => catBool('METRIC', 'meta-patrol'))
  /**
   * 成本巡检开关（T12-4，METRIC / cost-patrol，默认开）：开则在「待治理问题」面板展示「成本巡检」按钮；
   * 关闭后端点拒用、按钮隐藏（未配置按「开」处理，与 meta-patrol 同语义）。
   */
  const costPatrolEnabled = computed<boolean>(() => catBool('METRIC', 'cost-patrol'))
  /** 驾驶舱 AI 自动化大屏 Skill 总开关（COCKPIT / ai-dashboard，默认开）：关闭后隐藏「生成 AI 大屏」入口。 */
  const cockpitAiDashboardEnabled = computed<boolean>(() => catBool('COCKPIT', 'ai-dashboard'))

  /**
   * 点踩反馈修正采集开关（ASK / feedback-correction，默认开，T11-2）：
   * 开启后对话页「不准确」按钮先弹轻量可选采集层（期望 SQL/说明/错因）；
   * 关闭则退回一键直踩不弹层。仅 UI 门控——后端收到建议仍照常落库（向后兼容）。
   */
  const feedbackCorrectionEnabled = computed<boolean>(() => catBool('ASK', 'feedback-correction'))

  /**
   * 审计智能查询开关（AUDIT / nl-query，默认**关**，T11-3 涉 LLM 红线）：
   * 开启后审计页（平台/组织两视图）显示「AI 查」入口；关闭则整个面板隐藏。
   * 后端同名门控双重把关（未启用时端点 403），此处仅 UI 隐藏入口。
   */
  const auditNlQueryEnabled = computed<boolean>(() => catBool('AUDIT', 'nl-query', false))

  /**
   * 定时报表开关（T12-1，ASK / schedule，默认开）：开则高级查询模板行显示「定时」入口、
   * 「定时报表」管理页可用；关闭后端点拒用（40301）且调度扫描跳过，此处同步隐藏入口（未配置按「开」）。
   */
  const scheduleEnabled = computed<boolean>(() => catBool('ASK', 'schedule'))

  /**
   * 组织共享查询模板开关（T12-6，ASK / shared-templates，默认开）：开则模板列表合并共享模板、
   * 管理员/属主可行内切换「共享/收回」。仅收入口面——执行面数据驱动不受门控约束
   * （关开关不破坏存量共享模板可跑与已建定时报表，与后端 QueryService 同语义）。
   */
  const sharedTemplatesEnabled = computed<boolean>(() => catBool('ASK', 'shared-templates'))

  /**
   * 新建驾驶舱 / 大屏时的默认主题 code（THEME / default-dashboard-theme，平台默认 `"dark"`，
   * 租户可在配置中心覆盖）。
   *
   * <p><b>只作用于新建</b>：已存在的大屏沿用自己的 {@code dashboard.theme}，本值不回写、不迁移——
   * 否则平台改一次默认值会把所有人的大屏配色一起改掉，属于越权。
   *
   * <p>取不到（未登录 / 未配置 / 配置页被清空）时返回空串，调用方按各自内置默认处理，
   * 与"没有这项配置"的历史行为一致。
   */
  const defaultDashboardTheme = computed<string>(() => {
    const v = effective.value?.THEME?.['default-dashboard-theme']
    return typeof v === 'string' ? v.trim() : ''
  })

  return {
    effective,
    loaded,
    load,
    defaultView,
    maxDisplayRows,
    numberFormat,
    chartDefaults,
    attributionEnabled,
    attributionResultPanel,
    metricAutoAnalysisEnabled,
    issueDistillEnabled,
    issueDistillConvertEnabled,
    issuePushEnabled,
    metaPatrolEnabled,
    costPatrolEnabled,
    cockpitAiDashboardEnabled,
    feedbackCorrectionEnabled,
    auditNlQueryEnabled,
    scheduleEnabled,
    sharedTemplatesEnabled,
    defaultDashboardTheme
  }
})
