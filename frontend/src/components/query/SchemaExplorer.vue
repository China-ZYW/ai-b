<script setup lang="ts">
/**
 * 表结构浏览器：列出数据源里<b>所有表</b>，点表展开字段（列名 / 类型 / 注释）。
 *
 * <b>为什么抽成公共组件</b>：「高级查询」的两个 tab——手写 SQL 与可视化构造器——<b>必须</b>是同一块
 * 表结构：两个 tab 的交互语义相同（浏览表 → 展开字段 → 对字段做点什么），各写一份必然漂移成
 * 两套样式两种手感。本组件只负责"浏览与展开"，字段点下去要干什么由父组件通过插槽决定：
 *
 * - 默认（手写 SQL tab）：点字段行插列名，行尾「+ 限定名」插 {@code 表名.列名}；
 * - 传入 {@link #column-actions} 插槽（构造器）：换成「输出 / 筛选」按钮；
 * - 传入 {@link #table-tags} 插槽：表行右侧的标签（构造器放「主 / 已关联 / ⇄n」）；
 * - 传入 {@link #table-actions} 插槽：展开区顶部操作条（构造器放「设为主表 / + 整表输出」）。
 *
 * <b>三种搜索口径</b>：表名、表注释、列名。想找"所有带 amount 的列"直接输 amount 就能定位到表，
 * 这比一列一列翻有用得多。
 *
 * <b>为什么点表只展开、不换主表</b>：换主表在构造器里是破坏性操作（会清空既有勾选），
 * 而"我只是想看看这张表有哪些字段"才是点表的最高频意图。设主表走展开区里独立的按钮，
 * 避免"想看字段却把查询清了"。
 *
 * <b>数据只在这一份</b>：schema 由本组件加载，通过 {@link loaded} 事件交给父组件
 * （构造器用它填充中栏的列下拉）——同一份数据不会请求两次。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { datasourceApi } from '@/api/datasource'
import type { ColumnMeta, TableMeta } from '@/types'

const props = withDefaults(
  defineProps<{
    datasourceId: number | null
    /** 展开区高度（内部滚动，长列表不会把页面顶长）。 */
    height?: string
    /** 顶部是否显示「N 列」计数徽标。 */
    showCount?: boolean
    /** 需要高亮的表（构造器用它标记主表行）。 */
    highlightTables?: string[]
  }>(),
  { height: '420px', showCount: true, highlightTables: () => [] }
)

const emit = defineEmits<{
  pick: [payload: { table: string; column: string; qualified: string }]
  'pick-qualified': [payload: { table: string; column: string; qualified: string }]
  /** schema 加载完成（含被换数据源清空的 []），父组件据此同步自己的列下拉。 */
  loaded: [tables: TableMeta[]]
}>()

/** 库名前缀一律剥掉：schema 里是 mydb.orders，用户和 SQL 里都写 orders。 */
const shortOf = (name: string) => (name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : name)

const schema = ref<TableMeta[]>([])
const loading = ref(false)
const keyword = ref('')
/** 当前展开字段的表（可同时展开多张，点第二次收起）。 */
const expanded = ref<string[]>([])

const filtered = computed<TableMeta[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return schema.value
  return schema.value.filter((t) => {
    if (shortOf(t.name).toLowerCase().includes(k)) return true
    if ((t.comment ?? '').toLowerCase().includes(k)) return true
    return t.columns.some((c) => c.name.toLowerCase().includes(k))
  })
})

const isExpanded = (table: string) => expanded.value.includes(table)

const toggle = (table: string) => {
  expanded.value = isExpanded(table)
    ? expanded.value.filter((x) => x !== table)
    : [...expanded.value, table]
}

const columnsOf = (table: string) => {
  const hit = schema.value.find((t) => shortOf(t.name) === table)
  return hit?.columns ?? []
}

const primaryKeysOf = (table: string) => {
  const hit = schema.value.find((t) => shortOf(t.name) === table)
  return hit?.primaryKeys ?? []
}

/** 暴露给父组件：构造器要靠它判断"这张表现在展不展开"以及"展开时预填首列"。 */
defineExpose({ shortOf, isExpanded, toggle, columnsOf, primaryKeysOf, expanded })

const onPick = (table: string, column: ColumnMeta) => {
  emit('pick', { table, column: column.name, qualified: `${table}.${column.name}` })
}

const onPickQualified = (table: string, column: ColumnMeta) => {
  emit('pick-qualified', { table, column: column.name, qualified: `${table}.${column.name}` })
}

const load = async () => {
  if (!props.datasourceId) {
    schema.value = []
    expanded.value = []
    emit('loaded', [])
    return
  }
  loading.value = true
  try {
    schema.value = (await datasourceApi.schema(props.datasourceId)) ?? []
    expanded.value = []
    emit('loaded', schema.value)
  } catch {
    schema.value = []
    emit('loaded', [])
    ElMessage.warning('表结构读取失败，请检查数据源是否可用')
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, () => {
  void load()
})

// 挂载即兜底加载一次：父组件（尤其 v-show 常驻的构造器）不需要再自己拉 schema
void load()
</script>

<template>
  <div class="se-explorer">
    <div class="se-ex-head">
      <span class="se-ex-lb">表结构（{{ filtered.length }}/{{ schema.length }}）</span>
      <el-input
        v-model="keyword"
        placeholder="搜表名 / 注释 / 列名"
        size="small"
        clearable
        class="se-ex-search"
      />
    </div>

    <div v-if="loading" class="se-ex-hint">读取表结构中…</div>
    <div v-else-if="!schema.length" class="se-ex-hint">
      当前数据源还没有表，或读取失败。换一个数据源试试。
    </div>

    <div class="se-ex-body" :style="{ height }">
      <div
        v-for="t in filtered"
        :key="t.name"
        class="se-ex-tbl"
        :class="{ 'is-open': isExpanded(shortOf(t.name)), 'is-hl': highlightTables.includes(shortOf(t.name)) }"
      >
        <div class="se-ex-tbl-row" :title="t.comment || t.name" @click="toggle(shortOf(t.name))">
          <span class="se-ex-caret">{{ isExpanded(shortOf(t.name)) ? '▾' : '▸' }}</span>
          <span class="se-ex-tbl-name">{{ shortOf(t.name) }}</span>
          <!-- 表行右侧标签：手写 tab 不传（默认只显示列数），构造器放「主 / 已关联 / ⇄n」 -->
          <slot name="table-tags" :table="shortOf(t.name)" />
          <span v-if="showCount" class="se-ex-tbl-n">{{ t.columns.length }} 列</span>
        </div>

        <div v-if="isExpanded(shortOf(t.name))" class="se-ex-cols">
          <!-- 展开区顶部操作条：构造器放「设为主表 / + 整表输出」；手写 tab 不传则不渲染 -->
          <div v-if="$slots['table-actions']" class="se-ex-cols-bar">
            <slot name="table-actions" :table="shortOf(t.name)" />
          </div>
          <div v-if="primaryKeysOf(shortOf(t.name)).length" class="se-ex-pk">
            主键 {{ primaryKeysOf(shortOf(t.name)).join(', ') }}
          </div>
          <div
            v-for="c in t.columns"
            :key="c.name"
            class="se-ex-col"
            :title="c.comment || c.type"
            @click="onPick(shortOf(t.name), c)"
          >
            <span class="se-ex-col-name">{{ c.name }}</span>
            <span class="se-ex-col-type">{{ c.type }}</span>
            <span v-if="c.comment" class="se-ex-col-cmt">{{ c.comment }}</span>
            <!-- 字段行尾动作：默认「+ 限定名」（手写 SQL），构造器换成「输出 / 筛选」 -->
            <slot name="column-actions" :table="shortOf(t.name)" :column="c">
              <el-button
                size="small"
                text
                class="se-ex-col-qual"
                @click.stop="onPickQualified(shortOf(t.name), c)"
              >
                + 限定名
              </el-button>
            </slot>
          </div>
        </div>
      </div>
      <div v-if="!filtered.length && schema.length" class="se-ex-hint">没有匹配「{{ keyword }}」的表或列</div>
    </div>
  </div>
</template>

<style scoped>
.se-explorer {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.se-ex-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.se-ex-lb {
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}
.se-ex-search {
  flex: 1;
  min-width: 80px;
}
.se-ex-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  padding: 8px 0;
}
.se-ex-body {
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--bg-card);
}
.se-ex-tbl {
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.se-ex-tbl.is-hl {
  background: #f4f8ff;
}
.se-ex-tbl-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  font-size: 12px;
  cursor: pointer;
}
.se-ex-tbl-row:hover {
  background: var(--el-color-primary-light-9, #ecf5ff);
}
.se-ex-caret {
  color: var(--el-text-color-secondary, #909399);
  width: 10px;
}
.se-ex-tbl-name {
  font-family: Consolas, monospace;
  font-weight: 600;
  flex: none;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.se-ex-tbl-n {
  color: var(--el-text-color-placeholder, #c0c4cc);
  font-size: 11px;
  flex-shrink: 0;
}
.se-ex-cols {
  padding: 2px 0 6px 18px;
  background: #fafbfc;
}
.se-ex-cols-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
}
.se-ex-pk {
  font-size: 11px;
  color: #e6a23c;
  padding: 3px 0;
}
.se-ex-col {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px 2px 0;
  font-size: 12px;
  cursor: pointer;
}
.se-ex-col:hover {
  background: var(--el-color-primary-light-9, #ecf5ff);
}
.se-ex-col-name {
  font-family: Consolas, monospace;
  color: var(--el-text-color-regular, #606266);
  flex-shrink: 0;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.se-ex-col-type {
  color: #409eff;
  font-size: 11px;
  flex-shrink: 0;
  max-width: 72px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.se-ex-col-cmt {
  flex: 1;
  color: var(--el-text-color-secondary, #909399);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}
.se-ex-col-qual {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--el-color-primary, #409eff);
  opacity: 0;
  transition: opacity 0.15s;
}
.se-ex-col:hover .se-ex-col-qual {
  opacity: 1;
}
</style>
