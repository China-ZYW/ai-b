<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { dashboardApi } from '@/api/dashboard'
import WidgetCard from '@/components/dashboard/WidgetCard.vue'
import TechDecor from '@/components/dashboard/TechDecor.vue'
import DataFlowBar from '@/components/dashboard/DataFlowBar.vue'
import { fetchThemes, mergedTheme, themeVersion } from '@/theme/dashboardTheme'
import { useDashboardLinkageStore } from '@/stores/dashboardLinkage'
import type { DashboardDataVO, WidgetDataVO } from '@/types'

/**
 * 全屏数据大屏（独立于 AppLayout，深色克莱因蓝主题）：
 * 按驾驶舱布局以 CSS 网格等比铺满视口，标题 + 实时时钟，按 refreshSeconds 轮询
 * /api/dashboard/{id}/data 批量刷新各 widget；ESC 或按钮退出，可切换浏览器全屏。
 */
const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

/** 联动下钻（V4.44）：跨 widget 共享的整屏筛选条件；filter 变化时重新取数。 */
const linkage = useDashboardLinkageStore()

const loading = ref(false)
const data = ref<DashboardDataVO | null>(null)
const now = ref(new Date())
const lastUpdated = ref<Date | null>(null)
const isFullscreen = ref(false)

let clockTimer: number | undefined
let pollTimer: number | undefined

const widgets = computed<WidgetDataVO[]>(() => data.value?.widgets ?? [])
const cols = computed(() => data.value?.dashboard.cols || 12)
const maxRow = computed(() => widgets.value.reduce((m, w) => Math.max(m, w.y + w.h), 0) || 1)
const dashName = computed(() => data.value?.dashboard.name || '数据大屏')
/** 顶栏 Logo（styleConfig.logo）：大屏顶栏左侧渲染品牌标识；空则不显示。 */
const logoSrc = computed(() => data.value?.dashboard.styleConfig?.logo || null)

/** 自由画布模式（V4.44）：styleConfig.free=true 时弃用 12 列网格，改用 fx/fy/fw/fh 百分比绝对定位。 */
const isFree = computed(() => !!data.value?.dashboard.styleConfig?.free)

/** 大屏主题：背景 / 标题栏 / 文字 / 图表随 dashboard.theme 联动，并叠加自定义背景与样式覆盖（含远端主题）。 */
const th = computed(() => {
  void themeVersion.value // 建立响应式依赖：远端主题 hydration 完成后自动重解析
  const db = data.value?.dashboard
  return mergedTheme(db?.theme, db?.background, db?.styleConfig)
})
const screenStyle = computed(() => {
  const s: Record<string, string> = {
    '--sc-bg': th.value.screenBg,
    '--sc-header': th.value.headerBg,
    '--sc-text': th.value.textPrimary,
    '--sc-text2': th.value.textSecondary,
    '--sc-accent': th.value.accent,
    '--sc-card-border': th.value.cardBorder,
    '--sc-scale': String(th.value.fontScale ?? 1)
  }
  if (th.value.fontFamily) s.fontFamily = th.value.fontFamily
  return s
})

/** L1/L2 科技炫酷总开关：主题令牌 deco='tech' 时启用网格背景、数据流动与顶栏科技线。 */
const isTech = computed(() => th.value.deco === 'tech')

const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${cols.value}, 1fr)`,
  // 大屏整屏铺满、不翻页：行高按视口等比分配（minmax(0,1fr) 允许轨道收缩到 0 再均分），
  // 始终 fit 视口高度。去掉历史上「minmax(130px,1fr) + max(100%, 行数×144px)」的锁高——
  // 那会让行数多（如 2×2 且 h=4 → maxRow=8）的驾驶舱被撑到 1152px 而被迫纵向滚动。
  gridTemplateRows: `repeat(${maxRow.value}, minmax(0, 1fr))`,
  height: '100%'
}))

function cellStyle(w: WidgetDataVO): Record<string, string> {
  return {
    gridColumn: `${w.x + 1} / span ${w.w}`,
    gridRow: `${w.y + 1} / span ${w.h}`
  }
}

/** 自由画布模式：用 fx/fy/fw/fh 百分比绝对定位（0~100，缺省给一个兜底 20% 方块以免重叠到原点）。 */
function freeCellStyle(w: WidgetDataVO): Record<string, string> {
  const clamp = (v: number | null | undefined, fallback: number): number => {
    const n = typeof v === 'number' && Number.isFinite(v) ? v : fallback
    return Math.min(100, Math.max(0, n))
  }
  return {
    position: 'absolute',
    left: `${clamp(w.fx, 0)}%`,
    top: `${clamp(w.fy, 0)}%`,
    width: `${clamp(w.fw, 20)}%`,
    height: `${clamp(w.fh, 20)}%`
  }
}

/** 联动下钻：点击 widget 维度成员 → 写入整屏筛选，所有 widget 后续按同一维度切片刷新。 */
function onDrill(p: { dim: string; value: string | number; metricId?: number | null }): void {
  linkage.setFilter({ dim: p.dim, value: p.value })
}

const pad = (n: number): string => String(n).padStart(2, '0')
const timeStr = computed(() => `${pad(now.value.getHours())}:${pad(now.value.getMinutes())}:${pad(now.value.getSeconds())}`)
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const dateStr = computed(
  () =>
    `${now.value.getFullYear()}年${pad(now.value.getMonth() + 1)}月${pad(now.value.getDate())}日 · 星期${WEEK[now.value.getDay()]}`
)

async function loadData(): Promise<void> {
  if (!id || Number.isNaN(id)) return
  try {
    // 联动下钻：把当前整屏筛选条件透传给后端，按维度 SQL 切片；无筛选时走全量
    data.value = await dashboardApi.data(id, linkage.activeFilter)
    lastUpdated.value = new Date()
  } catch {
    // 拦截器已提示；保留上一帧数据继续展示
  }
}

/**
 * 刷新（带可见反馈）：置 refreshing=true 叠加整卡扫光，取数完成后至少保留 600ms 让扫光播完，
 * 再置 false。轮询、下钻、手动刷新按钮共用此入口，保证每次刷新用户都能看到动效。
 */
const refreshing = ref(false)
let refreshToken = 0
async function runRefresh(): Promise<void> {
  const token = ++refreshToken
  refreshing.value = true
  await loadData()
  window.setTimeout(() => {
    if (token === refreshToken) refreshing.value = false
  }, 600)
}

/** 联动下钻：筛选条件变化即重新取数（不必等下一轮轮询）。 */
watch(
  () => linkage.activeFilter,
  () => void runRefresh()
)

function startPoll(): void {
  if (pollTimer) window.clearInterval(pollTimer)
  const sec = data.value?.dashboard.refreshSeconds || 30
  pollTimer = window.setInterval(() => void runRefresh(), Math.max(5, sec) * 1000)
}

async function toggleFullscreen(): Promise<void> {
  try {
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen()
    } else {
      await document.exitFullscreen()
    }
  } catch {
    // 浏览器拒绝全屏时忽略
  }
}
function onFsChange(): void {
  isFullscreen.value = !!document.fullscreenElement
}

function exit(): void {
  if (document.fullscreenElement) void document.exitFullscreen()
  void router.push('/dashboards')
}
function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') exit()
  else if (e.key === 'f' || e.key === 'F') void toggleFullscreen()
}

onMounted(async () => {
  clockTimer = window.setInterval(() => (now.value = new Date()), 1000)
  document.addEventListener('keydown', onKey)
  document.addEventListener('fullscreenchange', onFsChange)
  void fetchThemes()
  loading.value = true
  await loadData()
  loading.value = false
  startPoll()
})

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  if (pollTimer) window.clearInterval(pollTimer)
  document.removeEventListener('keydown', onKey)
  document.removeEventListener('fullscreenchange', onFsChange)
})
</script>

<template>
  <div class="screen" :style="screenStyle">
    <!-- L1 科技网格背景（令牌 grid 驱动；absolute 不占布局，不影响网格布局） -->
    <TechDecor :grid="!!th.grid" :accent="th.accent" />

    <!-- 顶部标题栏 -->
    <header class="screen-head">
      <div class="sh-side sh-left">
        <span class="sh-badge">LIVE</span>
        <span class="sh-updated">{{ lastUpdated ? `${pad(lastUpdated.getHours())}:${pad(lastUpdated.getMinutes())}:${pad(lastUpdated.getSeconds())} 已刷新` : '加载中' }}</span>
      </div>
      <div class="sh-center">
        <img v-if="logoSrc" :src="logoSrc" class="sh-logo" alt="logo" />
        <h1 class="sh-title">{{ dashName }}</h1>
        <p class="sh-date">{{ dateStr }}</p>
      </div>
      <!-- 联动下钻筛选条：显示当前整屏下钻维度，点击 X 清除回到全量 -->
      <div v-if="linkage.hasFilter" class="sh-filter" @click="linkage.clearFilter()">
        <el-icon><Filter /></el-icon>
        <span class="sh-filter-text">下钻：{{ linkage.activeFilter?.dim }} = {{ linkage.activeFilter?.value }}</span>
        <el-icon class="sh-filter-x"><Close /></el-icon>
      </div>
      <div class="sh-side sh-right">
        <span class="sh-clock mono">{{ timeStr }}</span>
        <button class="sh-btn" :class="{ spinning: refreshing }" title="刷新数据" @click="runRefresh">
          <el-icon><Refresh /></el-icon>
        </button>
        <button class="sh-btn" :title="isFullscreen ? '退出全屏 (F)' : '全屏 (F)'" @click="toggleFullscreen">
          <el-icon><FullScreen /></el-icon>
        </button>
        <button class="sh-btn" title="退出 (ESC)" @click="exit">
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <!-- L1 顶栏科技线：底部霓虹渐变 + 中央高亮段 -->
      <div v-if="isTech" class="sh-techline" />
    </header>

    <!-- L2 数据流动管道：令牌 flow 开启时展示「数据源 → 计算 → 上屏」链路动效 -->
    <DataFlowBar :flow="!!th.flow" :accent="th.accent" />

    <!-- 网格主体 -->
    <div v-loading="loading" class="screen-body" :element-loading-background="th.dark ? 'rgba(2,12,38,0.6)' : 'rgba(255,255,255,0.6)'">
      <div
        v-if="widgets.length"
        class="screen-grid"
        :class="{ 'is-free': isFree }"
        :style="isFree ? undefined : gridStyle"
      >
        <div
          v-for="(w, wi) in widgets"
          :key="w.i"
          class="screen-cell"
          :style="isFree ? freeCellStyle(w) : cellStyle(w)"
        >
          <WidgetCard
            :title="w.title"
            :metric-id="w.metricId"
            :chart-spec="w.chartSpec"
            :chart-style="w.chartStyle"
            :style-override="w.style"
            :result="w.result"
            :infographic="w.infographic ?? null"
            :image="w.image ?? null"
            :list="w.list ?? null"
            :refreshing="refreshing"
            :tokens="th"
            :editable="false"
            :index="wi"
            empty-hint="未绑定指标"
            chart-height="100%"
            @drill="onDrill"
          />
        </div>
      </div>
      <div v-else-if="!loading" class="screen-empty">
        <el-icon :size="46"><DataBoard /></el-icon>
        <p>该驾驶舱尚未编排任何指标</p>
        <el-button type="primary" plain @click="router.push(`/dashboards/${id}/edit`)">去编辑</el-button>
      </div>
    </div>

    <!-- 装饰光晕 -->
    <div class="deco deco-tl" />
    <div class="deco deco-br" />
  </div>
</template>

<style scoped>
.screen {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--sc-bg);
  color: var(--sc-text);
}
.screen-head {
  height: 68px;
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 22px;
  background: var(--sc-header);
  border-bottom: 1px solid var(--sc-card-border);
  box-shadow: 0 6px 28px rgba(0, 10, 40, 0.4);
  position: relative;
  z-index: 3;
}
.sh-side {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sh-right {
  justify-content: flex-end;
}
.sh-badge {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 1px;
  color: #04123a;
  background: var(--sc-accent);
  padding: 3px 9px;
  border-radius: 20px;
  animation: pulse 1.8s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
.sh-updated {
  font-size: 12px;
  color: var(--sc-text2);
}
.sh-center {
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sh-logo {
  height: 34px;
  max-width: 160px;
  width: auto;
  object-fit: contain;
  margin-right: 12px;
  flex-shrink: 0;
}
.sh-title {
  margin: 0;
  font-size: calc(26px * var(--sc-scale, 1));
  font-weight: 800;
  letter-spacing: 2px;
  background: linear-gradient(90deg, var(--sc-text), var(--sc-accent));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.sh-date {
  margin: 2px 0 0;
  font-size: calc(12px * var(--sc-scale, 1));
  color: var(--sc-text2);
  letter-spacing: 1px;
}
.sh-clock {
  font-size: calc(22px * var(--sc-scale, 1));
  font-weight: 700;
  color: var(--sc-accent);
  letter-spacing: 1px;
}
.sh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid var(--sc-card-border);
  background: transparent;
  color: var(--sc-text);
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}
.sh-btn:hover {
  background: var(--sc-accent);
  border-color: transparent;
  color: #04123a;
}
/* 刷新中：按钮图标旋转，与整卡扫光呼应 */
.sh-btn.spinning .el-icon {
  animation: shSpin 0.8s linear infinite;
}
@keyframes shSpin {
  to {
    transform: rotate(360deg);
  }
}
/* 联动下钻筛选条（V4.44）：显示当前整屏下钻维度，点击 X 清除 */
.sh-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  margin-right: 12px;
  border-radius: 20px;
  font-size: 12px;
  color: #04123a;
  background: var(--sc-accent);
  cursor: pointer;
  white-space: nowrap;
}
.sh-filter-text {
  font-weight: 600;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sh-filter-x {
  font-size: 13px;
  opacity: 0.7;
}
.sh-filter:hover .sh-filter-x {
  opacity: 1;
}
.screen-body {
  flex: 1;
  min-height: 0;
  padding: 14px 18px 18px;
  position: relative;
  z-index: 2;
  /* widget 行数超出视口时纵向滚动，而非把每个 widget 压扁 */
  overflow-y: auto;
}
.screen-grid {
  display: grid;
  gap: 14px;
}
/* 自由画布模式：取消网格、改为相对定位容器，子项由 inline 百分比绝对定位（V4.44）
   ★必须给容器确定高度：子项用 height:X% 绝对定位，若容器高度塌陷为 0（绝对定位子项不贡献高度）
   则百分比解析基准为 0 → 所有 widget 高度 0、整屏空白。height:100% 解析自 .screen-body(flex:1)。 */
.screen-grid.is-free {
  display: block;
  position: relative;
  gap: 0;
  height: 100%;
}
/* P2 修复：自由画布子项加内边距(border-box)制造统一间距，与编辑器 .free-item 对齐，避免相邻 widget 贴边 */
.screen-grid.is-free .screen-cell {
  box-sizing: border-box;
  padding: 8px;
}
.screen-cell {
  min-width: 0;
  min-height: 0;
  /* 轨道高度确定后裁剪溢出内容，杜绝图表 canvas 反向撑大行高 */
  overflow: hidden;
}
.screen-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--sc-text2);
}
.screen-empty p {
  margin: 0;
  font-size: 15px;
}
.deco {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  pointer-events: none;
  z-index: 1;
}
.deco-tl {
  width: 360px;
  height: 360px;
  top: -120px;
  left: -100px;
  background: var(--sc-accent);
  opacity: 0.16;
}
.deco-br {
  width: 420px;
  height: 420px;
  bottom: -160px;
  right: -120px;
  background: var(--sc-accent);
  opacity: 0.2;
}
/* L1 顶栏科技线：压在标题栏底部的一道霓虹渐变，两端渐隐、中部偏亮 */
.sh-techline {
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, var(--sc-accent) 22%, var(--sc-accent) 78%, transparent);
  opacity: 0.85;
}
</style>
