<script setup lang="ts">
/**
 * 数据源管理主视图（T7.3 拆分后 1299→约 90 行）：
 * 全部状态与逻辑在 useDataSource 编排（core 分类 tab / form 表单+上传 / actions 行操作+抽屉），
 * UI 按域拆入 DsCardGrid / DsFormDialog / DsSchemaDrawer 组件，纯函数在 dsUtils。
 */
import { useDataSource } from './useDataSource'
import PermGuard from '@/components/PermGuard.vue'
import DsCardGrid from './DsCardGrid.vue'
import DsFormDialog from './DsFormDialog.vue'
import DsSchemaDrawer from './DsSchemaDrawer.vue'

const ctx = useDataSource()
</script>

<template>
  <div class="ds-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">数据源管理</h2>
        <p class="ph-desc">接入你的数据库或文件，凭证加密存储、查询只读安全访问。</p>
      </div>
      <PermGuard code="datasource:config:write" mode="disable" tip="当前角色无数据源管理权限">
        <el-button type="primary" class="add-btn" @click="ctx.openCreate">
          <el-icon><Plus /></el-icon> 新建数据源
        </el-button>
      </PermGuard>
    </div>

    <!-- 类型分类 tab：文件 → 关系型 → 数仓（文件最常用，置首） -->
    <el-tabs v-model="ctx.activeCategory" class="ds-tabs">
      <el-tab-pane v-for="t in ctx.categoryTabs" :key="t.key" :name="t.key">
        <template #label>
          <span class="ds-tab-label">{{ t.label }}<span class="ds-tab-count">{{ t.count }}</span></span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <DsCardGrid />

    <DsFormDialog />

    <DsSchemaDrawer />
  </div>
</template>

<style scoped>
.ds-view {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 32px;
}
/* 类型分类 tab（文件 / 关系型 / 数仓）：作为列向 flex 子项禁止压缩，
   否则内容一多 tab 会被挤扁（与「指标库告警条被压扁」同源的 flex 压缩家族缺陷）。 */
.ds-tabs {
  flex-shrink: 0;
  margin: -6px 0 0;
}
.ds-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.ds-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ds-tab-count {
  min-width: 20px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--fill-light, #f5f7fa);
  color: var(--text-secondary);
  font-size: 11.5px;
  line-height: 18px;
  text-align: center;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}
.ph-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0 0 4px;
}
.ph-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.add-btn {
  background: var(--grad-btn);
  border: none;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}
</style>
