<script setup lang="ts">
/**
 * 可视化查询构造器（「高级查询」的第二栏：不选 SQL，只勾选）。
 *
 * <b>设计取向</b>：把确定性分析能力交给"勾选"而不是"打字"——左侧浏览表与字段（点表展开）→
 * 选主表 / 按推荐关联加维表 → 勾输出列（可给度量套聚合） → 加筛选 → 排序 → 分页，
 * 最后「预览」看后端生成的 SQL，确认无误再运行。
 *
 * <b>表结构与手写 SQL tab 是同一个组件</b>（{@link SchemaExplorer}）：浏览、展开、搜索的交互与
 * 样式天然一致，本组件只通过插槽注入自己的动作——表行「主 / 已关联 / ⇄n」标签、
 * 展开区「设为主表 / + 整表输出」、字段行「输出 / 筛选」。schema 也只由该组件加载一份，
 * 通过 {@link loaded} 事件回填本组件的中栏列下拉，不重复请求。
 *
 * <b>为什么连接列不给用户填</b>：关联关系由服务端按同一口径算出（人工登记 &gt; 库中外键 &gt; 命名推断），
 * 前端只能从清单里挑。填错列名不会报错，只会安静地跑出一个数值错的结果——这是最难发现的一类问题，
 * 所以这里宁可多一次点击，也不放开手填。
 *
 * <b>只负责"配置"</b>：执行与结果渲染交回父页面，这样结果区（图表 / 明细 / 导出 / 历史）只有一份实现。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import SchemaExplorer from '@/components/query/SchemaExplorer.vue'
import {
  builderPreview,
  builderRelations,
  type BuilderFilter,
  type BuilderRelationVO,
  type BuilderRunRequest,
  type BuilderSelect,
  type BuilderSpec
} from '@/api/query'
import type { TableMeta } from '@/types'

const props = defineProps<{
  datasourceId: number | null
  maxRows: number
  chartType: string | null
}>()

const emit = defineEmits<{
  run: [payload: BuilderRunRequest]
  preview: [sql: string]
}>()

/** 运算符清单（与后端白名单一致，多一个都生成不出来）。 */
const OPS = [
  '=', '!=', '>', '>=', '<', '<=',
  'LIKE', 'NOT LIKE', 'IN', 'NOT IN', 'BETWEEN', 'IS NULL', 'IS NOT NULL'
]
const OP_LABELS: Record<string, string> = {
  '=': '等于', '!=': '不等于', '>': '大于', '>=': '大于等于', '<': '小于', '<=': '小于等于',
  LIKE: '包含', 'NOT LIKE': '不包含', 'IN': '属于', 'NOT IN': '不属于',
  BETWEEN: '介于', 'IS NULL': '为空', 'IS NOT NULL': '不为空'
}
/** 需要多个取值的运算符。 */
const MULTI_OPS = ['IN', 'NOT IN', 'BETWEEN']

const AGGS = ['', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX']
const AGG_LABELS: Record<string, string> = {
  '': '原始值', COUNT: '计数', SUM: '求和', AVG: '平均值', MIN: '最小值', MAX: '最大值'
}

const shortOf = (name: string) => name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : name

/** schema 由左栏 SchemaExplorer 加载后回填到这里（中栏的列下拉要用）。 */
const schema = ref<TableMeta[]>([])
const mainTable = ref<string>('')
const relations = ref<BuilderRelationVO[]>([])
const joins = ref<BuilderRelationVO[]>([])
const selects = ref<BuilderSelect[]>([])
const filters = ref<BuilderFilter[]>([])
const sorts = ref<{ table: string; column: string; desc: boolean }[]>([])
const limit = ref<number>(1000)
const offset = ref<number>(0)
const previewSql = ref<string>('')
const previewParams = ref<number>(0)

/** 左栏浏览器引用：主表设好后让它自动展开该表（刚选完表，最想看的就是它的字段）。 */
const explorerRef = ref<InstanceType<typeof SchemaExplorer> | null>(null)

/** 参与查询的表：主表 + 已关联的维表。 */
const tables = computed<string[]>(() => {
  const out: string[] = []
  if (mainTable.value) out.push(mainTable.value)
  for (const j of joins.value) {
    const name = j.table === mainTable.value ? j.refTable : j.table
    if (name && !out.includes(name)) out.push(name)
  }
  return out
})

const metaOf = (table: string) => schema.value.find((t) => shortOf(t.name) === table)

const columnsOf = (table: string) => metaOf(table)?.columns ?? []

const columnLabel = (table: string, column: string) => {
  const hit = columnsOf(table).find((c) => c.name === column)
  return hit?.comment?.trim() ? `${column}（${hit.comment.trim()}）` : column
}

const isMain = (table: string) => table === mainTable.value

/** 该表有几条推荐关联（左右任一侧都算）。 */
const relCountOf = (table: string) =>
  relations.value.filter((r) => r.table === table || r.refTable === table).length

const isJoined = (table: string) =>
  joins.value.some((j) => (j.table === mainTable.value ? j.refTable : j.table) === table)

const resetQuery = () => {
  joins.value = []
  selects.value = []
  filters.value = []
  sorts.value = []
  previewSql.value = ''
  previewParams.value = 0
}

/** 左栏 schema 就绪：回填中栏下拉数据源；首次加载默认选第一张表当主表。 */
const onSchemaLoaded = (tables: TableMeta[]) => {
  schema.value = tables
  if (!tables.length) {
    mainTable.value = ''
    return
  }
  if (!mainTable.value || !tables.some((t) => shortOf(t.name) === mainTable.value)) {
    mainTable.value = shortOf(tables[0].name)
  }
}

const loadRelations = async () => {
  if (!props.datasourceId || !mainTable.value) {
    relations.value = []
    return
  }
  try {
    relations.value = (await builderRelations(props.datasourceId, mainTable.value)) ?? []
  } catch {
    relations.value = []
  }
}

// 换数据源：查询清空重来；schema 清空与重载由左栏组件自己完成（会再走一次 onSchemaLoaded）
watch(() => props.datasourceId, () => {
  mainTable.value = ''
  resetQuery()
})

watch(mainTable, async (t) => {
  resetQuery()
  if (!t) {
    relations.value = []
    return
  }
  // 主表默认就是展开的：刚选完表，最想看的就是它的字段
  if (explorerRef.value && !explorerRef.value.isExpanded(t)) explorerRef.value.toggle(t)
  void loadRelations()
  // 默认带上主表第一个列，让用户"点运行就能出结果"，而不是面对一个空表单
  const first = columnsOf(t)[0]
  if (first) selects.value.push({ table: t, column: first.name, agg: '' })
})

const onSetMain = (table: string) => {
  if (isMain(table)) return
  mainTable.value = table
  ElMessage.info(`已把 ${table} 设为主表，此前的勾选已清空`)
}

const onAddJoin = (r: BuilderRelationVO) => {
  if (joins.value.some((x) => x.key === r.key)) {
    ElMessage.info('这张表已经关联进来了')
    return
  }
  joins.value.push(r)
  const nb = r.table === mainTable.value ? r.refTable : r.table
  // 关联表的列同样可以输出 / 筛选，自动预填一列，省一次下拉
  const first = columnsOf(nb)[0]
  if (first) selects.value.push({ table: nb, column: first.name, agg: '' })
}

const onRemoveJoin = (key: string) => {
  const removed = joins.value.filter((j) => j.key === key)
  joins.value = joins.value.filter((j) => j.key !== key)
  // 关联表一旦摘掉，它身上的字段也要撤掉，否则生成时会被判成"列不属于已选的表"
  const names = removed.flatMap((j) => [j.table, j.refTable])
  const drop = (t: string) => names.includes(t) || names.includes(shortOf(t))
  selects.value = selects.value.filter((s) => !drop(s.table))
  filters.value = filters.value.filter((f) => !drop(f.table))
  sorts.value = sorts.value.filter((s) => !drop(s.table))
  relations.value.push(...removed)
}

// ---- 从展开的字段清单里直接把列加进查询，省一次"回到上面找下拉" ----
const addSelect = (table: string, column: string) => {
  if (selects.value.some((s) => s.table === table && s.column === column)) {
    ElMessage.info(`${table}.${column} 已在输出列里`)
    return
  }
  selects.value.push({ table, column, agg: '' })
}

const addFilter = (table: string, column: string) => {
  filters.value.push({ table, column, op: '=', values: [''] })
}

const addAllColumns = (table: string) => {
  let n = 0
  for (const c of columnsOf(table)) {
    if (selects.value.some((s) => s.table === table && s.column === c.name)) continue
    selects.value.push({ table, column: c.name, agg: '' })
    n++
  }
  ElMessage.info(n ? `${table} 的 ${n} 个列已加入输出列` : `${table} 的列都已在输出列里`)
}

const buildSpec = (): BuilderSpec | null => {
  if (!mainTable.value) {
    ElMessage.warning('请先选择主表')
    return null
  }
  return {
    mainTable: mainTable.value,
    joins: joins.value.map((j) => ({ relationKey: j.key })),
    selects: selects.value,
    filters: filters.value,
    sorts: sorts.value,
    limit: limit.value,
    offset: offset.value
  }
}

const onPreview = async () => {
  const spec = buildSpec()
  if (!spec) return
  try {
    const res = await builderPreview({
      datasourceId: props.datasourceId as number,
      spec,
      maxRows: props.maxRows,
      chartType: props.chartType
    })
    previewSql.value = res.sql
    previewParams.value = res.paramCount
    emit('preview', res.sql)
  } catch (e: unknown) {
    previewSql.value = ''
    ElMessage.error((e as Error).message ?? '生成失败')
  }
}

const onRun = () => {
  const spec = buildSpec()
  if (!spec) return
  emit('run', {
    datasourceId: props.datasourceId as number,
    spec,
    maxRows: props.maxRows,
    chartType: props.chartType
  })
}

const onAddFilter = () => {
  if (!mainTable.value) return
  filters.value.push({ table: mainTable.value, column: '', op: '=', values: [''] })
}
const onAddSort = () => {
  if (!mainTable.value) return
  sorts.value.push({ table: mainTable.value, column: '', desc: true })
}
const drop = <T,>(arr: T[], i: number) => arr.splice(i, 1)

/**
 * 外部回填：把一份构造器规格还原进当前表单（用于"加载模板 / 历史"）。
 * 必须在主表变更触发的 watch flush 之后再写 joins/selects，否则会被 resetQuery 清空。
 */
const loadSpec = async (spec: BuilderSpec) => {
  if (!spec || !spec.mainTable) return
  mainTable.value = spec.mainTable
  await nextTick()            // 等 watch(mainTable) flush：resetQuery + 默认列 + loadRelations 发请求
  await loadRelations()       // 确保关联清单就绪（覆盖 watch 里的并发加载）
  joins.value = (spec.joins ?? [])
    .map((j) => relations.value.find((r) => r.key === j.relationKey))
    .filter((r): r is BuilderRelationVO => Boolean(r))
  selects.value = (spec.selects ?? []).map((s) => ({ ...s }))
  filters.value = (spec.filters ?? []).map((f) => ({ ...f, values: [...(f.values ?? [])] }))
  sorts.value = (spec.sorts ?? []).map((s) => ({ table: s.table, column: s.column, desc: !!s.desc }))
  limit.value = spec.limit ?? 1000
  offset.value = spec.offset ?? 0
  previewSql.value = ''
}

/** 外部读取：当前表单对应的构造器规格（用于"保存模板"）。 */
const getSpec = (): BuilderSpec | null => buildSpec()

defineExpose({ loadSpec, getSpec })
</script>

<template>
  <div class="qb">
    <div class="qb-bar">
      <el-tag v-if="mainTable" size="small" type="info">
        主表 {{ mainTable }}（{{ columnsOf(mainTable).length }} 列）
      </el-tag>
    </div>

    <el-row :gutter="12">
      <!-- 左栏：与手写 SQL tab 同一个表结构浏览器，样式天然一致 -->
      <el-col :span="7">
        <div class="qb-sec qb-sec-full">
          <SchemaExplorer
            ref="explorerRef"
            :datasource-id="datasourceId"
            height="calc(100vh - 400px)"
            :highlight-tables="mainTable ? [mainTable] : []"
            @loaded="onSchemaLoaded"
          >
            <template #table-tags="{ table }">
              <el-tag v-if="isMain(table)" size="small">主</el-tag>
              <el-tag v-if="isJoined(table)" size="small" type="success">已关联</el-tag>
              <span
                v-if="relCountOf(table) && !isMain(table)"
                class="qb-tbl-rel"
                :title="`有 ${relCountOf(table)} 条推荐关联`"
              >⇄ {{ relCountOf(table) }}</span>
            </template>

            <template #table-actions="{ table }">
              <el-button
                v-if="!isMain(table)"
                size="small"
                text
                type="primary"
                @click.stop="onSetMain(table)"
              >设为主表</el-button>
              <span v-else class="qb-cols-tag">这张表就是主表</span>
              <el-button size="small" text @click.stop="addAllColumns(table)">+ 整表输出</el-button>
            </template>

            <template #column-actions="{ table, column }">
              <el-button size="small" text @click.stop="addSelect(table, column.name)">输出</el-button>
              <el-button size="small" text @click.stop="addFilter(table, column.name)">筛选</el-button>
            </template>
          </SchemaExplorer>
        </div>
      </el-col>

      <!-- 中栏：关联 / 输出列 / 筛选 / 排序 -->
      <el-col :span="10">
        <!-- 关联推荐：只能从服务端清单里挑 -->
        <div class="qb-sec">
          <span class="qb-lb">关联维表（{{ joins.length }}）</span>
          <div v-if="!relations.length" class="qb-hint">
            这张表在当前数据源里没有可用的关联（需要至少两张表，且存在外键或命名可推断的键）。
          </div>
          <ul class="qb-rel">
            <li v-for="r in relations" :key="r.key">
              <span class="qb-rel-text">
                {{ r.table }}.{{ r.column }} = {{ r.refTable }}.{{ r.refColumn }}
                <el-tag v-if="r.source === 'MANUAL'" size="small" type="success">已登记</el-tag>
                <el-tag v-else-if="r.source === 'FOREIGN_KEY'" size="small" type="warning">外键</el-tag>
                <el-tag v-else size="small" type="info">推断</el-tag>
              </span>
              <el-button size="small" text @click="onAddJoin(r)">关联</el-button>
            </li>
          </ul>

          <div v-for="j in joins" :key="j.key" class="qb-join">
            <el-tag type="primary" size="small">
              JOIN {{ j.table === mainTable ? j.refTable : j.table }}
            </el-tag>
            <span class="qb-join-on">
              ON t1.{{ j.table === mainTable ? j.column : j.refColumn }}
              = t2.{{ j.table === mainTable ? j.refColumn : j.column }}
            </span>
            <el-button size="small" text type="danger" @click="onRemoveJoin(j.key)">移除</el-button>
          </div>
        </div>

        <!-- 输出列 -->
        <div class="qb-sec">
          <span class="qb-lb">输出列（{{ selects.length }}）</span>
          <div v-for="(s, i) in selects" :key="i" class="qb-row">
            <el-select v-model="s.table" placeholder="表" class="qb-t" size="small">
              <el-option v-for="t in tables" :key="t" :label="t" :value="t" />
            </el-select>
            <el-select v-model="s.column" placeholder="列" class="qb-c" size="small">
              <el-option
                v-for="c in columnsOf(s.table)"
                :key="c.name"
                :label="columnLabel(s.table, c.name)"
                :value="c.name"
              />
            </el-select>
            <el-select v-model="s.agg" placeholder="聚合" class="qb-a" size="small">
              <el-option v-for="a in AGGS" :key="a" :label="AGG_LABELS[a]" :value="a" />
            </el-select>
            <el-button size="small" text type="danger" @click="drop(selects, i)">移除</el-button>
          </div>
          <el-button size="small" text @click="selects.push({ table: mainTable, column: '', agg: '' })">
            + 添加列
          </el-button>
          <div class="qb-hint">有任一列选了聚合函数时，其余「原始值」列会自动进入 GROUP BY。</div>
        </div>

        <!-- 筛选 -->
        <div class="qb-sec">
          <span class="qb-lb">筛选（{{ filters.length }}）</span>
          <div v-for="(f, i) in filters" :key="i" class="qb-row">
            <el-select v-model="f.table" placeholder="表" class="qb-t" size="small">
              <el-option v-for="t in tables" :key="t" :label="t" :value="t" />
            </el-select>
            <el-select v-model="f.column" placeholder="列" class="qb-c" size="small">
              <el-option
                v-for="c in columnsOf(f.table)"
                :key="c.name"
                :label="columnLabel(f.table, c.name)"
                :value="c.name"
              />
            </el-select>
            <el-select v-model="f.op" class="qb-o" size="small">
              <el-option v-for="op in OPS" :key="op" :label="OP_LABELS[op] ?? op" :value="op" />
            </el-select>
            <template v-if="!['IS NULL', 'IS NOT NULL'].includes(f.op)">
              <el-input v-if="!MULTI_OPS.includes(f.op)" v-model="f.values[0]" placeholder="值" class="qb-v" size="small" />
              <template v-else>
                <template v-for="(_, vi) in f.values" :key="vi">
                  <el-input v-model="f.values[vi]" placeholder="值" class="qb-v" size="small" />
                </template>
                <el-button
                  size="small"
                  text
                  :disabled="f.values.length >= 5"
                  @click="f.values.push('')"
                >+</el-button>
              </template>
            </template>
            <el-button size="small" text type="danger" @click="drop(filters, i)">移除</el-button>
          </div>
          <el-button size="small" text @click="onAddFilter">+ 添加筛选</el-button>
          <div class="qb-hint">取值一律走参数绑定，不拼进 SQL。</div>
        </div>

        <!-- 排序与分页 -->
        <div class="qb-sec">
          <span class="qb-lb">排序（{{ sorts.length }}）</span>
          <div v-for="(s, i) in sorts" :key="i" class="qb-row">
            <el-select v-model="s.table" placeholder="表" class="qb-t" size="small">
              <el-option v-for="t in tables" :key="t" :label="t" :value="t" />
            </el-select>
            <el-select v-model="s.column" placeholder="列" class="qb-c" size="small">
              <el-option
                v-for="c in columnsOf(s.table)"
                :key="c.name"
                :label="columnLabel(s.table, c.name)"
                :value="c.name"
              />
            </el-select>
            <el-select v-model="s.desc" class="qb-a" size="small">
              <el-option :label="'降序'" :value="true" />
              <el-option :label="'升序'" :value="false" />
            </el-select>
            <el-button size="small" text type="danger" @click="drop(sorts, i)">移除</el-button>
          </div>
          <el-button size="small" text @click="onAddSort">+ 添加排序</el-button>

          <div class="qb-row qb-limit">
            <span class="qb-lb qb-inline">每页行数</span>
            <el-input-number v-model="limit" :min="1" :max="10000" :step="100" size="small" />
            <span class="qb-lb qb-inline">偏移</span>
            <el-input-number v-model="offset" :min="0" :max="100000" :step="100" size="small" />
          </div>
        </div>
      </el-col>

      <!-- 右栏：生成的 SQL 与运行 -->
      <el-col :span="7">
        <div class="qb-sec">
          <span class="qb-lb">生成的 SQL</span>
          <pre v-if="previewSql" class="qb-sql">{{ previewSql }}</pre>
          <div v-else class="qb-hint">点击下方「预览 SQL」生成，确认无误再「运行」。</div>
          <div v-if="previewParams" class="qb-hint">该 SQL 含 {{ previewParams }} 个绑定参数。</div>
          <div class="qb-actions">
            <el-button size="small" @click="onPreview">预览 SQL</el-button>
            <el-button size="small" type="primary" :disabled="!mainTable" @click="onRun">运行</el-button>
          </div>
        </div>
        <div class="qb-sec">
          <span class="qb-lb">快速上手</span>
          <ol class="qb-tips">
            <li>在左栏点表展开字段；</li>
            <li>点「设为主表」把该表设为查询的主表；</li>
            <li>字段行右侧的「输出 / 筛选」直接加进下方清单；</li>
            <li>需要多表时回中栏从推荐关联里「关联」。</li>
          </ol>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.qb-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.qb-sec {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 8px 10px 10px;
  margin-bottom: 10px;
}
.qb-sec-full {
  display: flex;
  flex-direction: column;
}
.qb-lb {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  margin-bottom: 6px;
}
.qb-lb-inline {
  display: inline-block;
  margin: 0 6px 0 0;
}
.qb-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.7;
}
.qb-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.qb-t {
  width: 96px;
}
.qb-c {
  flex: 1;
  min-width: 0;
}
.qb-a {
  width: 108px;
}
.qb-o {
  width: 96px;
}
.qb-v {
  width: 92px;
}
.qb-limit {
  margin-top: 8px;
}
.qb-rel {
  list-style: none;
  margin: 0 0 6px;
  padding: 0;
}
.qb-rel li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter, #ebeef5);
  font-size: 12px;
}
.qb-rel-text {
  flex: 1;
  font-family: Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qb-join {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 12px;
  font-family: Consolas, monospace;
}
.qb-join-on {
  flex: 1;
  color: var(--el-text-color-regular, #606266);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qb-sql {
  background: var(--fill-light);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 8px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}
.qb-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
/* ---- 插槽内容（表行标签 / 展开区操作条）的样式：slot 内容属父作用域，留在本组件 ---- */
.qb-tbl-rel {
  flex: none;
  color: #e6a23c;
  font-size: 11px;
}
.qb-cols-tag {
  color: #409eff;
  font-size: 11px;
}
.qb-tips {
  margin: 0;
  padding-left: 16px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.9;
}
/* 窄屏放不下三栏时，各栏退化为整行 */
@media (max-width: 1280px) {
  .qb .el-col {
    width: 100%;
    max-width: 100%;
  }
}
</style>
