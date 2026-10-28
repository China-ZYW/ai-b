<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import { adminOverviewApi } from '@/api/admin'
import type { AdminOverviewTrendVO, AdminOverviewVO } from '@/types'
import { formatNumber } from '@/utils/format'
import BaseChart from '@/components/chart/BaseChart.vue'

/**
 * 平台概览（超管后台首页）：跨租户聚合统计卡片 + 运营趋势图表区。
 *
 * <p>卡片数据来自 /admin/overview；图表数据来自 /admin/overview/trend
 * （近 14 天问数/失败趋势、新增账号/租户、套餐分布、活跃租户 TOP8，后端已补零对齐）。
 * 两接口并行加载，趋势接口失败只降级图表区、不影响卡片。
 */
const loading = ref(false)
const data = ref<AdminOverviewVO | null>(null)
const trend = ref<AdminOverviewTrendVO | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [overview, trendData] = await Promise.allSettled([
      adminOverviewApi.get(),
      adminOverviewApi.getTrend()
    ])
    if (overview.status === 'fulfilled') data.value = overview.value
    if (trendData.status === 'fulfilled') trend.value = trendData.value
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 失败率（%）。 */
function failRate(): string {
  const d = data.value
  if (!d || d.totalQueryCount <= 0) return '0%'
  return `${((d.failedQueryCount / d.totalQueryCount) * 100).toFixed(1)}%`
}

/** 今日问数较昨日环比（%）：昨日为 0 时返回 null（不展示徽标，避免"从 0 涨 22 次=∞%"的误导）。 */
const todayDelta = computed<number | null>(() => {
  const q = trend.value?.queryTrend
  if (!q || q.length < 2) return null
  const today = q[q.length - 1]?.total ?? 0
  const yesterday = q[q.length - 2]?.total ?? 0
  if (yesterday <= 0) return null
  return ((today - yesterday) / yesterday) * 100
})

/* ------------------------------ 图表配置 ------------------------------ */

const AXIS_LABEL = { color: '#8a94a6', fontSize: 11 }
const SPLIT_LINE = { lineStyle: { color: '#eef1f6', type: 'dashed' as const } }

/** 问数趋势（近 14 天）：总量平滑面积线 + 失败红线。 */
const queryOption = computed<EChartsOption | null>(() => {
  const q = trend.value?.queryTrend
  if (!q) return null
  const dates = q.map((p) => p.date.slice(5))
  return {
    color: ['#2f6bff', '#ff6b6b'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 14, itemHeight: 8, icon: 'roundRect', textStyle: { color: '#5a6478', fontSize: 12 } },
    grid: { left: 8, right: 18, top: 38, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLine: { lineStyle: { color: '#dfe4ec' } },
      axisTick: { show: false },
      axisLabel: AXIS_LABEL
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    series: [
      {
        name: '问数次数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: q.map((p) => p.total),
        lineStyle: { width: 2.6 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(47,107,255,0.22)' },
              { offset: 1, color: 'rgba(47,107,255,0.01)' }
            ]
          }
        }
      },
      { name: '失败次数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: q.map((p) => p.failed), lineStyle: { width: 2 } }
    ]
  }
})

/** 套餐分布：环形图，中心展示租户总数。 */
const planOption = computed<EChartsOption | null>(() => {
  const d = trend.value?.planDistribution
  if (!d) return null
  const total = d.reduce((s, x) => s + x.value, 0)
  return {
    color: ['#2f6bff', '#00c2ff', '#6a5cff', '#16c784', '#ff9f43'],
    tooltip: { trigger: 'item', formatter: '{b}: {c} 家（{d}%）' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10, textStyle: { color: '#5a6478', fontSize: 12 } },
    title: {
      text: formatNumber(total),
      subtext: '租户总数',
      left: 'center',
      top: '36%',
      textStyle: { fontSize: 24, fontWeight: 700, color: '#1f2733' },
      subtextStyle: { fontSize: 12, color: '#8a94a6' }
    },
    series: [
      {
        name: '套餐分布',
        type: 'pie',
        radius: ['54%', '76%'],
        center: ['50%', '44%'],
        itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: d.map((x) => ({ name: x.name, value: x.value }))
      }
    ]
  }
})

/** 用户增长（近 14 天）：新增账号柱状 + 新增租户折线。 */
const signupOption = computed<EChartsOption | null>(() => {
  const s = trend.value?.signupTrend
  if (!s) return null
  const dates = s.map((p) => p.date.slice(5))
  return {
    color: ['#00b8d4', '#6a5cff'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 14, itemHeight: 8, icon: 'roundRect', textStyle: { color: '#5a6478', fontSize: 12 } },
    grid: { left: 8, right: 18, top: 38, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#dfe4ec' } },
      axisTick: { show: false },
      axisLabel: AXIS_LABEL
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    series: [
      {
        name: '新增账号',
        type: 'bar',
        barWidth: 10,
        data: s.map((p) => p.users),
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#22d3ee' },
              { offset: 1, color: '#0ea5c4' }
            ]
          }
        }
      },
      { name: '新增租户', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: s.map((p) => p.tenants), lineStyle: { width: 2 } }
    ]
  }
})

/** 活跃租户 TOP8（按累计问数）：横向条形，条端标数值。 */
const topOption = computed<EChartsOption | null>(() => {
  const t = trend.value?.topTenants
  if (!t) return null
  // topTenants 按问数降序；横向条形 y 轴自下而上渲染，反转后最大的排在最上面
  const asc = [...t].reverse()
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const arr = (params ?? []) as Array<{ value?: number | string }>
        return `累计问数 ${arr?.[0]?.value ?? 0} 次`
      }
    },
    grid: { left: 8, right: 48, top: 10, bottom: 4, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    yAxis: {
      type: 'category',
      data: asc.map((x) => x.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#4a5468', fontSize: 12, width: 96, overflow: 'truncate' }
    },
    series: [
      {
        name: '累计问数',
        type: 'bar',
        barWidth: 12,
        data: asc.map((x) => x.value),
        itemStyle: {
          borderRadius: [0, 6, 6, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: '#6a5cff' },
              { offset: 1, color: '#2f6bff' }
            ]
          }
        },
        label: { show: true, position: 'right', color: '#8a94a6', fontSize: 11 }
      }
    ]
  }
})
</script>

<template>
  <div v-loading="loading" class="overview">
    <div class="page-head row-between">
      <div>
        <h2 class="ph-title">平台概览</h2>
        <p class="ph-sub">跨租户实时聚合，掌握平台整体运营态势</p>
      </div>
      <el-tooltip content="刷新数据" placement="top">
        <el-button :icon="'Refresh'" circle @click="load" />
      </el-tooltip>
    </div>

    <!-- 规模指标 -->
    <div class="stat-grid">
      <div class="stat-card hairline-top">
        <div class="stat-icon ic-blue"><el-icon :size="22"><OfficeBuilding /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.tenantCount) }}</span>
          <span class="stat-label">租户总数</span>
        </div>
      </div>
      <div class="stat-card hairline-top">
        <div class="stat-icon ic-cyan"><el-icon :size="22"><UserFilled /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.userCount) }}</span>
          <span class="stat-label">账号总数</span>
        </div>
      </div>
      <div class="stat-card hairline-top">
        <div class="stat-icon ic-violet"><el-icon :size="22"><Coin /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.datasourceCount) }}</span>
          <span class="stat-label">数据源</span>
        </div>
      </div>
      <div class="stat-card hairline-top">
        <div class="stat-icon ic-green"><el-icon :size="22"><Cpu /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.llmConfigCount) }}</span>
          <span class="stat-label">大模型配置</span>
        </div>
      </div>
      <div class="stat-card hairline-top">
        <div class="stat-icon ic-blue"><el-icon :size="22"><DataBoard /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.dashboardCount) }}</span>
          <span class="stat-label">驾驶舱</span>
        </div>
      </div>
    </div>

    <!-- 问数量指标 -->
    <div class="stat-grid">
      <div class="stat-card wide">
        <div class="stat-icon ic-cyan"><el-icon :size="22"><ChatDotRound /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">
            {{ formatNumber(data?.todayQueryCount) }}
            <span v-if="todayDelta !== null" class="delta" :class="todayDelta >= 0 ? 'up' : 'down'">
              {{ todayDelta >= 0 ? '▲' : '▼' }} {{ Math.abs(todayDelta).toFixed(0) }}%
            </span>
          </span>
          <span class="stat-label">今日问数 · 较昨日</span>
        </div>
      </div>
      <div class="stat-card wide">
        <div class="stat-icon ic-blue"><el-icon :size="22"><TrendCharts /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.totalQueryCount) }}</span>
          <span class="stat-label">累计问数</span>
        </div>
      </div>
      <div class="stat-card wide">
        <div class="stat-icon ic-orange"><el-icon :size="22"><WarnTriangleFilled /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ formatNumber(data?.failedQueryCount) }}</span>
          <span class="stat-label">失败次数</span>
        </div>
      </div>
      <div class="stat-card wide">
        <div class="stat-icon ic-violet"><el-icon :size="22"><PieChart /></el-icon></div>
        <div class="stat-body">
          <span class="stat-value">{{ failRate() }}</span>
          <span class="stat-label">失败率</span>
        </div>
      </div>
    </div>

    <!-- 图表区：问数趋势 + 套餐分布 -->
    <div class="chart-row main">
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">问数趋势</span>
          <span class="chart-sub">近 14 天 · 总量与失败</span>
        </div>
        <BaseChart :option="queryOption" height="300px" :loading="loading" />
      </div>
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">套餐分布</span>
          <span class="chart-sub">按租户数</span>
        </div>
        <BaseChart :option="planOption" height="300px" :loading="loading" />
      </div>
    </div>

    <!-- 图表区：用户增长 + 活跃租户 -->
    <div class="chart-row half">
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">用户增长</span>
          <span class="chart-sub">近 14 天 · 新增账号 / 租户</span>
        </div>
        <BaseChart :option="signupOption" height="280px" :loading="loading" />
      </div>
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">活跃租户 TOP 8</span>
          <span class="chart-sub">按累计问数</span>
        </div>
        <BaseChart :option="topOption" height="280px" :loading="loading" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.page-head {
  margin-bottom: 2px;
}
.ph-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0;
  color: var(--text-primary);
}
.ph-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}
.stat-grid:last-of-type {
  grid-template-columns: repeat(4, 1fr);
}
.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: box-shadow 0.25s ease, transform 0.25s ease;
}
.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}
.ic-blue { background: var(--grad-brand); }
.ic-cyan { background: var(--grad-accent); }
.ic-violet { background: linear-gradient(135deg, #6a5cff, #1e5ae8); }
.ic-green { background: linear-gradient(135deg, #16c784, #0e9f6e); }
.ic-orange { background: linear-gradient(135deg, #ff9f43, #ff6b6b); }
.stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.stat-value {
  font-size: 26px;
  font-weight: 800;
  color: var(--text-primary);
  line-height: 1.1;
  display: flex;
  align-items: baseline;
  gap: 8px;
  white-space: nowrap;
}
.stat-label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 6px;
}
/* 环比徽标：涨红跌绿（中式配色习惯） */
.delta {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  line-height: 1.4;
}
.delta.up {
  color: #e5484d;
  background: rgba(229, 72, 77, 0.1);
}
.delta.down {
  color: #0e9f6e;
  background: rgba(22, 199, 132, 0.12);
}
/* 图表区布局：上排 2:1（问数趋势 | 套餐分布），下排 1:1（用户增长 | 活跃租户 TOP8）。
   两个 row 各自 2 列，卡片数必须与列数一致，否则会换行留白（原 span2 在 2 列网格里等于独占整行）。 */
.chart-row {
  display: grid;
  gap: 16px;
  /* 同一行内卡片等高，保证卡片底边与图表底边对齐 */
  align-items: stretch;
}
.chart-row.main {
  grid-template-columns: 2fr 1fr;
}
.chart-row.half {
  grid-template-columns: 1fr 1fr;
}
.chart-card {
  background: var(--bg-card);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 18px 20px 12px;
  min-width: 0;
}
.chart-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 4px;
}
.chart-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.chart-sub {
  font-size: 12px;
  color: var(--text-placeholder);
}
@media (max-width: 1200px) {
  .stat-grid,
  .stat-grid:last-of-type {
    grid-template-columns: repeat(2, 1fr);
  }
  .chart-row.main,
  .chart-row.half {
    grid-template-columns: 1fr;
  }
}
</style>
