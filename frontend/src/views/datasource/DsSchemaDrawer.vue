<script setup lang="ts">
/**
 * 表结构抽屉（T7.3 自 DataSourceView 逐字搬移）：当前数据源的表/列折叠列表。
 */
import { useDsCtx } from './useDataSource'

const ctx = useDsCtx()
</script>

<template>
  <el-drawer v-model="ctx.schemaVisible" :title="`表结构 · ${ctx.schemaSource?.name || ''}`" size="520px">
    <div v-loading="ctx.schemaLoading" class="schema-body">
      <el-empty v-if="!ctx.schemaLoading && ctx.schemaData.length === 0" description="未读取到表结构" />
      <el-collapse v-else accordion>
        <el-collapse-item v-for="t in ctx.schemaData" :key="t.name" :name="t.name">
          <template #title>
            <span class="tbl-name mono">{{ t.name }}</span>
            <span v-if="t.comment" class="tbl-comment truncate">{{ t.comment }}</span>
            <el-tag size="small" effect="plain" round class="tbl-count">{{ t.columns.length }} 列</el-tag>
          </template>
          <el-table :data="t.columns" size="small" border>
            <el-table-column prop="name" label="字段" min-width="120" />
            <el-table-column prop="type" label="类型" min-width="100" />
            <el-table-column prop="comment" label="注释" min-width="120" show-overflow-tooltip />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>
  </el-drawer>
</template>

<style scoped>
.schema-body {
  min-height: 120px;
}
.tbl-name {
  font-weight: 700;
  color: var(--ikb);
  font-size: 13.5px;
}
.tbl-comment {
  margin-left: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  max-width: 180px;
}
.tbl-count {
  margin-left: auto;
  margin-right: 8px;
}
</style>
