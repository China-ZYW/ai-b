<script setup lang="ts">
/**
 * 数据源卡片列表（T7.3 自 DataSourceView 逐字搬移）：
 * 当前分类过滤的卡片网格 + 全局空态 + 分类内空态。纯展示，操作全部经 ctx。
 */
import { useDsCtx } from './useDataSource'
import {
  CATEGORY_LABEL,
  formatDateTime,
  isFileType,
  sheetSummaryOf,
  summary,
  syncLabel
} from './dsUtils'
import { datasourceTypeLabel } from '@/utils/format'
import PermGuard from '@/components/PermGuard.vue'

const ctx = useDsCtx()
</script>

<template>
  <!-- 全局空态：一个数据源都还没有 -->
  <el-empty v-if="!ctx.dsStore.loading && ctx.dsStore.isEmpty" description="还没有数据源，先接入一个吧">
    <PermGuard code="datasource:config:write" mode="disable" tip="当前角色无数据源管理权限">
      <el-button type="primary" @click="ctx.openCreate">新建数据源</el-button>
    </PermGuard>
  </el-empty>

  <!-- 卡片列表（按当前分类过滤） -->
  <div v-else v-loading="ctx.dsStore.loading" class="ds-grid">
    <div
      v-for="d in ctx.filteredList"
      :key="d.id"
      class="ds-card card card-hover"
      :class="{ current: ctx.dsStore.currentId === d.id }"
    >
      <div class="ds-top">
        <div class="ds-icon"><el-icon :size="20"><Coin /></el-icon></div>
        <div class="ds-info">
          <span class="ds-name truncate">{{ d.name }}</span>
          <el-tag size="small" effect="plain" type="primary" round>{{ datasourceTypeLabel(d.type) }}</el-tag>
        </div>
        <el-tooltip v-if="ctx.dsStore.currentId === d.id" content="当前问数使用的数据源" placement="top">
          <span class="current-dot" />
        </el-tooltip>
      </div>

      <div class="ds-summary mono truncate" :title="summary(d)">{{ summary(d) }}</div>
      <div class="ds-meta">
        <span v-if="!isFileType(d.type) && d.username" class="meta-item">
          <el-icon><User /></el-icon>{{ d.username }}
        </span>
        <span v-if="!isFileType(d.type) && d.extraCatalogs?.length" class="meta-item">
          <el-icon><Link /></el-icon>跨库 {{ d.extraCatalogs.length }} 个
        </span>
        <span v-if="isFileType(d.type)" class="meta-item">
          <el-icon><Grid /></el-icon>{{ sheetSummaryOf(d) }}
        </span>
        <span class="meta-item">
          <el-icon><Lock /></el-icon>{{ d.hasPassword ? '已设密码' : '无密码' }}
        </span>
        <span
          class="meta-item"
          :class="{ stale: !d.lastSchemaSyncAt }"
          :title="`最近同步：${formatDateTime(d.lastSchemaSyncAt)}`"
        >
          <el-icon><Clock /></el-icon>{{ syncLabel(d) }}
        </span>
      </div>

      <div class="ds-actions">
        <PermGuard code="datasource:config:test" mode="disable" tip="当前角色无连通性测试权限">
          <el-button size="small" text :loading="ctx.testBusy.isBusy(d.id)" @click="ctx.testSaved(d)">
            <el-icon><Connection /></el-icon> 测试
          </el-button>
        </PermGuard>
        <PermGuard code="datasource:schema:read" mode="disable" tip="当前角色无表结构查看权限">
          <el-button size="small" text @click="ctx.openSchema(d)">
            <el-icon><Grid /></el-icon> 表结构
          </el-button>
        </PermGuard>
        <PermGuard code="semantic:meta:read" mode="disable" tip="当前角色无结构对账权限">
          <el-button size="small" text @click="ctx.goDrift(d)">
            <el-icon><Aim /></el-icon> 结构对账
          </el-button>
        </PermGuard>
        <PermGuard code="datasource:config:write" mode="disable" tip="当前角色无数据源编辑权限">
          <el-button
            size="small"
            text
            :loading="ctx.syncBusy.isBusy(d.id)"
            title="重新扫描表结构（新增表/删列后立即生效）"
            @click="ctx.syncSchema(d)"
          >
            <el-icon><Refresh /></el-icon> 同步
          </el-button>
        </PermGuard>
        <PermGuard code="datasource:config:write" mode="disable" tip="当前角色无数据源编辑权限">
          <el-button size="small" text @click="ctx.openEdit(d)">
            <el-icon><Edit /></el-icon> 编辑
          </el-button>
        </PermGuard>
        <PermGuard code="datasource:config:delete" mode="disable" tip="当前角色无数据源删除权限">
          <el-button size="small" text type="danger" class="ops-danger" title="删除数据源" @click="ctx.removeDs(d)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </PermGuard>
      </div>
    </div>

    <!-- 该分类下无数据源（库里是有的，只是不在这一个分类里） -->
    <el-empty
      v-if="!ctx.dsStore.loading && ctx.filteredList.length === 0"
      class="tab-empty"
      :description="`「${CATEGORY_LABEL[ctx.activeCategory]}」下暂无数据源`"
      :image-size="60"
    />
  </div>
</template>

<style scoped>
/* 空态占满整行，否则会缩在网格的第一格里 */
.tab-empty {
  grid-column: 1 / -1;
}
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.ds-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: relative;
}
.ds-card.current {
  border-color: rgba(0, 47, 167, 0.28);
  box-shadow: 0 0 0 2px var(--ikb-pale);
}
.ds-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ds-icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: var(--grad-brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--shadow-brand);
}
.ds-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.ds-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.ds-info .el-tag {
  align-self: flex-start;
}
.current-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 3px rgba(22, 199, 132, 0.2);
  flex-shrink: 0;
}
.ds-summary {
  font-size: 12.5px;
  color: var(--text-regular);
  background: var(--ikb-ghost);
  padding: 8px 11px;
  border-radius: var(--radius-sm);
}
.ds-meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta-item .el-icon {
  color: var(--ikb-lighter);
}
/* 从未同步过：提示表结构可能是旧快照（新增表可能没进来） */
.meta-item.stale .el-icon {
  color: var(--el-color-warning);
}
.ds-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  border-top: 1px solid var(--border-light);
  padding-top: 10px;
  margin-top: 2px;
}
.ds-actions .el-button {
  font-size: 12.5px;
}
</style>
