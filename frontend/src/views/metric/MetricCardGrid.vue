<script setup lang="ts">
/**
 * 指标卡片网格（T7.3 自 MetricView 拆出）：卡片渲染（AI/分组/图表类型标签、
 * 新鲜度标黄）与操作按钮组（预览/分享/加入驾驶舱/移动分组/编辑/删除）+ 空态引导。
 * 状态与操作全部走 useMetricCtx（provide/inject），本组件只负责渲染与事件接线。
 */
import { useMetricCtx } from './useMetric'
import { formatDateTime } from '@/utils/format'

const ctx = useMetricCtx()
</script>

<template>
  <div v-loading="ctx.loading" class="metric-grid">
    <div
      v-for="(m, mi) in ctx.filtered"
      :key="m.id"
      class="metric-card card-hover is-deco"
      :class="{ 'is-stale': m.stale }"
      :style="{ animationDelay: mi * 55 + 'ms' }"
    >
      <div class="mc-deco">
        <i class="mc-corner mc-tl" />
        <i class="mc-corner mc-tr" />
        <i class="mc-corner mc-bl" />
        <i class="mc-corner mc-br" />
        <i class="mc-scan" />
      </div>
      <div class="mc-head">
        <span class="mc-name truncate">{{ m.alias || m.name }}</span>
        <span class="mc-tags">
          <el-tag v-if="m.source === 'AI'" size="small" type="success" effect="plain" round>AI</el-tag>
          <el-tag v-if="m.groupId && ctx.groupNameMap[m.groupId]" size="small" type="info" effect="plain" round>
            {{ ctx.groupNameMap[m.groupId] }}
          </el-tag>
          <el-tag size="small" effect="plain" round>{{ ctx.chartLabel(m.chartSpec) }}</el-tag>
        </span>
      </div>
      <p class="mc-desc">{{ m.description || '暂无描述' }}</p>
      <div class="mc-meta">
        <span class="mono small">{{ ctx.dsName(m.datasourceId) }}</span>
        <el-tooltip
          :content="m.neverRefreshed ? '该指标保存后从未成功取数，展示的是首次保存时的结果' : `数据最后刷新：${formatDateTime(m.refreshedAt)}｜超过 SLA ${m.freshnessHours ?? '全局'} 小时即标黄`"
          placement="top"
        >
          <el-tag v-if="m.stale" size="small" type="warning" effect="dark" round class="fr-tag">
            <el-icon><WarningFilled /></el-icon> {{ ctx.freshnessText(m) }}
          </el-tag>
          <span v-else class="muted small">{{ ctx.freshnessText(m) }}</span>
        </el-tooltip>
      </div>
      <div class="mc-actions">
        <el-button size="small" text @click="ctx.openPreview(m)">
          <el-icon><View /></el-icon> 预览
        </el-button>
        <el-button v-if="ctx.can('share:link:write')" size="small" text @click="ctx.openShare(m)">
          <el-icon><Share /></el-icon> 分享
        </el-button>
        <el-button v-if="ctx.can('dashboard:item:write')" size="small" text @click="ctx.openAdd(m)">
          <el-icon><DataBoard /></el-icon> 加入驾驶舱
        </el-button>
        <el-dropdown
          v-if="ctx.can('metric:item:write')"
          trigger="click"
          @command="(gid: number | null) => ctx.moveMetric(m, gid)"
        >
          <el-button size="small" text title="移动到分组"><el-icon><Folder /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :command="null">未分组</el-dropdown-item>
              <el-dropdown-item v-for="g in ctx.flatGroupOptions" :key="g.id" :command="g.id">
                {{ g.indent }}{{ g.name }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-if="ctx.can('metric:item:write')" size="small" text title="编辑指标" @click="ctx.openEdit(m)">
          <el-icon><Edit /></el-icon>
        </el-button>
        <el-button v-if="ctx.can('metric:item:delete')" size="small" text type="danger" class="ops-danger" title="删除指标" @click="ctx.removeMetric(m)">
          <el-icon><Delete /></el-icon>
        </el-button>
      </div>
    </div>

    <el-empty
      v-if="!ctx.loading && ctx.filtered.length === 0"
      class="empty-block"
      :description="ctx.emptyText"
    >
      <el-button
        v-if="!ctx.keyword && ctx.selectedKey === 'all'"
        type="primary"
        @click="ctx.router.push('/app/chat')"
      >
        去问数 <el-icon><ArrowRight /></el-icon>
      </el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  /* 同驾驶舱列表：行高按内容自适应、行起点对齐顶部；卡片多时由本容器自身滚动 */
  grid-auto-rows: min-content;
  align-content: start;
  gap: 16px;
  min-height: 200px;
  flex: 1;
  overflow-y: auto;
  padding: 4px 2px 8px;
}
.metric-card {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 列表入场淡入+上浮，错峰由 :style animation-delay 控制 */
  animation: metricEnter 0.5s cubic-bezier(0.22, 0.61, 0.36, 1) both;
}
/* ---------- 科技感装饰（复用 WidgetCard 的 corner/scan/glow 思路，absolute overlay 不占布局） ---------- */
.metric-card.is-deco {
  --mc-accent: #002fa7;
  position: relative;
  overflow: hidden;
  border-color: rgba(0, 47, 167, 0.22);
  box-shadow: 0 0 18px -12px var(--mc-accent);
}
.metric-card.is-deco:hover {
  border-color: var(--mc-accent);
  box-shadow: 0 0 22px -8px var(--mc-accent);
}
.mc-deco {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 2;
  overflow: hidden;
  border-radius: inherit;
}
.mc-corner {
  position: absolute;
  width: 9px;
  height: 9px;
  border: 1.5px solid var(--mc-accent);
  opacity: 0.5;
}
.mc-tl {
  top: 4px;
  left: 4px;
  border-right: none;
  border-bottom: none;
}
.mc-tr {
  top: 4px;
  right: 4px;
  border-left: none;
  border-bottom: none;
}
.mc-bl {
  bottom: 4px;
  left: 4px;
  border-right: none;
  border-top: none;
}
.mc-br {
  bottom: 4px;
  right: 4px;
  border-left: none;
  border-top: none;
}
.mc-scan {
  position: absolute;
  top: 0;
  left: 0;
  width: 36%;
  height: 1.5px;
  background: linear-gradient(90deg, transparent, var(--mc-accent), transparent);
  opacity: 0.6;
  animation: mcScan 3.8s ease-in-out infinite;
}
@keyframes mcScan {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(320%);
  }
}
@media (prefers-reduced-motion: reduce) {
  .mc-scan {
    animation: none;
  }
}
@keyframes metricEnter {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.mc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.mc-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
/* 卡片标题右侧标签组（所属分组 + 图表类型）：成组靠右，避免被 space-between 拆散 */
.mc-tags {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.mc-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.5;
  height: 37px;
  overflow: hidden;
}
.mc-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
/* ---------- 数据新鲜度（P2）：过期指标整卡标黄，与卡片内 warning 标签呼应 ---------- */
.metric-card.is-stale {
  border-color: rgba(230, 162, 60, 0.55);
  box-shadow: 0 0 20px -12px #e6a23c;
}
.metric-card.is-stale:hover {
  border-color: #e6a23c;
}
.fr-tag {
  font-size: 11.5px;
  padding: 0 6px;
  height: 20px;
}
.small {
  font-size: 11.5px;
}
.mc-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  border-top: 1px solid var(--border-light);
  padding-top: 8px;
  margin-top: 2px;
}
.mc-actions .el-button {
  padding: 4px 6px;
}
.empty-block {
  grid-column: 1 / -1;
}
</style>
