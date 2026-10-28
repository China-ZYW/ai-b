<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { InfographicItem, InfographicSpec } from '@/types'

/**
 * 信息图渲染组件（AntV Infographic 的唯一封装点）。
 *
 * <p><b>为什么单独封一层</b>：引擎目前是 0.2.x（尚未 1.0），模板名/API 仍有变动可能。
 * 把调用收敛在这一处、依赖锁死版本，将来升级或换引擎只改本文件，不污染驾驶舱各处。
 *
 * <p><b>懒加载</b>：引擎体量不小（还带 d3 / rough.js 等依赖），而绝大多数驾驶舱并不会放信息图。
 * 因此用动态 {@code import()}——只有真的渲染了信息图组件才会去下载，首屏体积不受影响。
 *
 * <p><b>与数据图表的关系</b>：互补，不是替代。图表绑定 metricId 实时跑 SQL；信息图是内容驱动的
 * 叙事组件（流程 / 时间线 / 对比 / 要点），补的是"这一屏在讲什么"。本组件不发任何数据请求。
 *
 * <p><b>两种数据形态</b>：flat=单组 items（列表/流程/金字塔/扇形/瀑布/关系）；grouped=分组，
 * 每个分组是一根节点、其 children 为组内条目（对比 / 层级类模板，如 SWOT、左右对比）。引擎两种
 * 结构都读 {@code data.items}：flat 直接给要点数组，grouped 给「带 children 的根节点数组」。
 */
const props = withDefaults(
  defineProps<{
    /** 信息图配置（模板 + 主题 + 数据项 / 分组）。为空时渲染占位。 */
    spec: InfographicSpec | null
    /** 当前驾驶舱主题是否深色：用于挑选引擎主题（引擎主题名无效时会自动降级）。 */
    dark?: boolean
    /** 卡片字体族（跟随大屏主题），用于 SVG 文本与整体观感一致。 */
    fontFamily?: string | null
    /** 驾驶舱主色（accent）：跟随驾驶舱时注入引擎作为 colorPrimary，使信息图主色与驾驶舱一致。 */
    accent?: string | null
  }>(),
  { dark: false, fontFamily: null, accent: null }
)

const host = ref<HTMLDivElement | null>(null)
const loading = ref(false)
const failed = ref('')

/** 引擎实例（any：0.x 类型未稳定，避免被类型漂移绊住；调用点已做能力判断）。 */
let instance: any = null

/** 是否为分组模式：显式 mode=grouped，或未声明 mode 但带了分组数据（向后兼容）。 */
function isGrouped(spec: InfographicSpec | null): boolean {
  if (!spec) return false
  if (spec.mode === 'grouped') return true
  return spec.mode == null && !!spec.groups && spec.groups.length > 0
}

/** 是否无有效内容（用于占位判断，flat/grouped 各自判断）。 */
const isEmpty = computed<boolean>(() => {
  const spec = props.spec
  if (!spec) return true
  if (isGrouped(spec)) {
    return !(spec.groups || []).some((g) => (g.label || '').trim().length > 0)
  }
  return !spec.items || spec.items.length === 0
})

function destroy(): void {
  if (instance && typeof instance.destroy === 'function') {
    try {
      instance.destroy()
    } catch {
      // 销毁失败无副作用，忽略
    }
  }
  instance = null
}

/**
 * 引擎主题名：显式配置（spec.theme，如 'light'/'dark'/'hand-drawn'）优先；
 * 未配置（跟随驾驶舱）时按驾驶舱明暗返回 'light'/'dark'（不再传 undefined，
 * 否则引擎回落默认主题、主色恒为粉红，与驾驶舱不一致）。accent 通过 themeConfig 另行注入。
 */
function themeOf(): string {
  if (props.spec?.theme) return props.spec.theme as string
  return props.dark ? 'dark' : 'light'
}

/** 单个要点 → 引擎 datum：label 必带，desc 非空才带；value 仅当可转有限数值时带（StatisticsDatum.value 为 number）。 */
function toDatum(it: InfographicItem): Record<string, unknown> {
  const item: Record<string, unknown> = { label: it.label }
  if (it.desc != null && it.desc !== '') item.desc = it.desc
  const v = it.value
  if (v !== null && v !== undefined && v !== '') {
    const n = typeof v === 'number' ? v : Number(v)
    if (Number.isFinite(n)) item.value = n
  }
  return item
}

/** 按数据形态组装引擎 data.items。 */
function buildItems(spec: InfographicSpec): Record<string, unknown>[] {
  if (isGrouped(spec)) {
    return (spec.groups || [])
      .filter((g) => (g.label || '').trim().length > 0)
      .map((g) => {
        const node: Record<string, unknown> = { label: g.label }
        if (g.desc != null && g.desc !== '') node.desc = g.desc
        node.children = (g.children || []).map(toDatum)
        return node
      })
  }
  return spec.items.map(toDatum)
}

async function render(): Promise<void> {
  const el = host.value
  const spec = props.spec
  if (!el) return
  destroy()
  failed.value = ''
  if (!spec || isEmpty.value) {
    el.innerHTML = ''
    return
  }
  loading.value = true
  try {
    // 动态引入：见类注释「懒加载」
    const mod = await import('@antv/infographic')
    const data = { items: buildItems(spec) }
    const base = {
      container: el,
      width: '100%',
      height: '100%',
      template: spec.template,
      data,
      // 背景透明（修复：信息图背景色不跟随驾驶舱背景色）：
      // 引擎默认按解析出的 themeConfig.colorBg 给 SVG 刷底色（light 主题=#ffffff），
      // 会盖住驾驶舱卡片/大屏背景。svg.background=false 让 renderBackground 跳过绘制，
      // 背景改为透出父级（WidgetCard 的 --wc-bg = 大屏卡片背景/逐组件覆盖），即「跟随驾驶舱背景色」。
      // 注意：只关背景绘制，不改 themeConfig.colorBg —— colorBg 仍参与派生文字/图形色，
      // 保持与主题明暗一致（改它会让文字色判断错位）。
      svg: { background: false }
    }
    // 与驾驶舱一致：主题名按明暗取 light/dark，主色用驾驶舱 accent（themeConfig.colorPrimary）。
    // 不变条件是「主色必须与驾驶舱一致」——故降级只丢主题名、保留 accent。
    const themeConfig = props.accent ? { colorPrimary: props.accent } : undefined
    // 引擎 0.x 的 InfographicOptions 类型较窄（value 必填 number 等），调用点做能力判断，这里放宽类型
    instance = new mod.Infographic({ ...base, theme: themeOf(), themeConfig } as Record<string, unknown>)
    try {
      await instance.render()
    } catch {
      // 主题名可能随引擎版本变动：拿掉主题名（保留 accent）再试一次，宁可默认配色也不要整块空白
      destroy()
      instance = new mod.Infographic({ ...base, themeConfig } as Record<string, unknown>)
      await instance.render()
    }
  } catch (e: any) {
    failed.value = e?.message || '信息图渲染失败'
  } finally {
    loading.value = false
  }
}

onMounted(render)
watch(() => [props.spec, props.dark, props.fontFamily, props.accent], render, { deep: true })
onBeforeUnmount(destroy)
</script>

<template>
  <div class="info-widget" :style="fontFamily ? { fontFamily } : undefined">
    <div v-if="isEmpty" class="iw-placeholder">
      <el-icon :size="26"><Document /></el-icon>
      <p>未配置信息图内容</p>
    </div>
    <div v-else-if="failed" class="iw-placeholder is-error">
      <el-icon :size="26"><WarningFilled /></el-icon>
      <p>{{ failed }}</p>
    </div>
    <div v-else v-loading="loading" class="iw-canvas" ref="host" />
  </div>
</template>

<style scoped>
.info-widget {
  height: 100%;
  width: 100%;
  overflow: hidden;
}
.iw-canvas {
  height: 100%;
  width: 100%;
}
.iw-canvas :deep(svg) {
  width: 100%;
  height: 100%;
}
.iw-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wc-muted, #909399);
}
.iw-placeholder p {
  margin: 0;
  font-size: 12.5px;
}
.iw-placeholder.is-error {
  color: #e6a23c;
}
</style>
