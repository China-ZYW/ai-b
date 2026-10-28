<script setup lang="ts">
/**
 * 指标分组侧栏（T7.3 自 MetricView 拆出）：多层级分组目录树 + 分组新建/重命名对话框。
 * 与驾驶舱列表同构；resource_group 的 METRIC 域，与 DASHBOARD 树互不越界。
 * 状态与操作全部走 useMetricCtx（provide/inject），本组件只负责渲染与事件接线。
 */
import { useMetricCtx } from './useMetric'

const ctx = useMetricCtx()
</script>

<template>
  <aside class="group-sidebar">
    <div class="gs-head">
      <span class="gs-title">分组</span>
      <el-button
        v-if="ctx.can('metric:item:write')"
        size="small"
        text
        class="gs-add"
        @click="ctx.openCreateGroup(null)"
      >
        <el-icon><Plus /></el-icon> 新建
      </el-button>
    </div>
    <el-tree
      class="gs-tree"
      :data="ctx.treeData"
      node-key="id"
      :props="{ label: 'name', children: 'children' }"
      :expand-on-click-node="false"
      highlight-current
      default-expand-all
      :current-node-key="ctx.selectedKey"
      @node-click="ctx.onNodeClick"
    >
      <template #default="{ data }">
        <span class="gs-node">
          <span class="gs-node-label truncate">{{ data.name }}</span>
          <span v-if="!data.isSpecial && ctx.can('metric:item:write')" class="gs-node-acts">
            <el-icon title="新建子分组" @click.stop="ctx.openCreateGroup(data.id as number)"><Plus /></el-icon>
            <el-icon title="重命名 / 移动" @click.stop="ctx.openEditGroup(data)"><Edit /></el-icon>
            <el-icon
              v-if="ctx.can('metric:item:delete')"
              title="删除（仅空分组）"
              @click.stop="ctx.removeGroup(data)"
            ><Delete /></el-icon>
          </span>
        </span>
      </template>
    </el-tree>

    <!-- 分组新建 / 重命名·移动对话框（与驾驶舱列表同构） -->
    <el-dialog
      v-model="ctx.groupDialogVisible"
      :title="ctx.groupFormMode === 'create' ? '新建分组' : '重命名 / 移动分组'"
      width="460px"
      align-center
    >
      <el-form label-width="84px" class="group-form">
        <el-form-item label="分组名称">
          <el-input v-model="ctx.groupForm.name" placeholder="例如：经营分析" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="上级分组">
          <el-select v-model="ctx.groupForm.parentId" clearable placeholder="根级（不挂在任一分组下）" style="width: 100%">
            <el-option v-for="g in ctx.groupParentOptions" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
        <p class="muted small gf-hint">空=根级；选择上级即把本分组（含其下整棵子树）挂到该分组之下。</p>
      </el-form>
      <template #footer>
        <el-button @click="ctx.groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="ctx.groupSubmitting" @click="ctx.submitGroup">保存</el-button>
      </template>
    </el-dialog>
  </aside>
</template>

<style scoped>
/* ---------- 分组侧栏（原 .metric-view 布局子树；侧栏内部滚动） ---------- */
.group-sidebar {
  width: 224px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 12px 10px;
  overflow-y: auto;
}
.gs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 10px;
}
.gs-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-secondary);
}
.gs-add {
  padding: 2px 6px;
}
.gs-tree {
  flex: 1;
  border: none;
  background: transparent;
}
.gs-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 6px;
}
.gs-node-label {
  flex: 1;
  min-width: 0;
}
.gs-node-acts {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
}
.gs-node:hover .gs-node-acts {
  opacity: 1;
}
.gs-node-acts .el-icon {
  cursor: pointer;
  font-size: 14px;
  color: var(--text-secondary);
  padding: 2px;
  border-radius: 3px;
}
.gs-node-acts .el-icon:hover {
  background: var(--fill-light, #f5f7fa);
  color: var(--brand, #409eff);
}
.gf-hint {
  margin: 0;
  line-height: 1.6;
}
/* gf-hint 用了 small 工具类：原视图中 scoped 定义，拆分后由本组件自持 */
.small {
  font-size: 11.5px;
}
</style>
