<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { DataSourceVO } from '@/types'
import { datasourceApi } from '@/api/datasource'
import {
  schemaDriftApi,
  type AffectedDashboard,
  type BrokenMetric,
  type DanglingAclItem,
  type DanglingRelation,
  type DriftPruneRequest,
  type DriftReport,
  type OrphanSemantic,
  type SchemaWatchResult
} from '@/api/schemaDrift'
import PermGuard from '@/components/PermGuard.vue'

const route = useRoute()
const router = useRouter()

/** 数据源下拉（复用数据源列表接口，凭证不回传）。 */
const dsOptions = ref<DataSourceVO[]>([])
const selectedDs = ref<number | null>(null)

/** 检测报告与加载态。 */
const report = ref<DriftReport | null>(null)
const checking = ref(false)

/** T12-3 结构变更感知：结果弹窗。 */
const watching = ref(false)
const watchRes = ref<SchemaWatchResult | null>(null)
const watchDialog = ref(false)

async function runWatch(): Promise<void> {
  if (selectedDs.value == null) {
    ElMessage.warning('请先选择数据源')
    return
  }
  watching.value = true
  try {
    watchRes.value = await schemaDriftApi.watch(selectedDs.value)
    const r = watchRes.value
    if (r.baselineCreated) {
      ElMessage.success(r.note ?? '首轮基准已建立，下次感知起才做变更比对')
    } else if (r.impacts.length > 0) {
      ElMessage.warning(`发现变更砸到 ${r.impacts.length} 项资产，已推送创建人预警`)
      watchDialog.value = true
    } else {
      ElMessage.success(r.note ?? '未发现打断引用的变更')
    }
  } catch {
    // 拦截器已提示
  } finally {
    watching.value = false
  }
}

function assetKindText(t: string): string {
  return t === 'SAVED_QUERY' ? '查询模板' : '指标'
}

/** 可删除项的勾选（el-table 多选回传的行）。 */
const selSemantic = ref<OrphanSemantic[]>([])
const selRelation = ref<DanglingRelation[]>([])
const selAcl = ref<DanglingAclItem[]>([])

const totalDrift = computed<number>(() => {
  const r = report.value
  if (!r) return 0
  return (
    r.orphanSemantics.length +
    r.danglingRelations.length +
    r.danglingAcls.length +
    r.brokenMetrics.length +
    r.affectedDashboards.length
  )
})

/** 是否有可清理的勾选项（语义行 / 关联 / 权限）。 */
const hasPrunableSelection = computed<boolean>(
  () => selSemantic.value.length > 0 || selRelation.value.length > 0 || selAcl.value.length > 0
)

onMounted(async () => {
  try {
    dsOptions.value = await datasourceApi.list()
  } catch {
    // 拦截器已提示
  }
  const qid = route.query.datasourceId
  if (qid != null && qid !== '') {
    const id = Number(qid)
    selectedDs.value = Number.isFinite(id) ? id : null
    if (selectedDs.value != null) {
      void runCheck()
    }
  }
})

async function runCheck(): Promise<void> {
  if (selectedDs.value == null) {
    ElMessage.warning('请先选择数据源')
    return
  }
  checking.value = true
  report.value = null
  selSemantic.value = []
  selRelation.value = []
  selAcl.value = []
  try {
    report.value = await schemaDriftApi.check(selectedDs.value)
    if (totalDrift.value === 0) {
      ElMessage.success('未发现结构漂移，所有下游引用均与当前物理表结构一致')
    } else {
      ElMessage.info(`检测到 ${totalDrift.value} 处失配，请逐项核对`)
    }
  } catch {
    // 拦截器已提示
  } finally {
    checking.value = false
  }
}

/** ACL 物理表归类：USER+FIELD → user_field_acl，以此类推。 */
function aclTableOf(item: DanglingAclItem): keyof DriftPruneRequest {
  if (item.target === 'USER') {
    return item.kind === 'FIELD' ? 'userFieldAclIds' : 'userTableAclIds'
  }
  return item.kind === 'FIELD' ? 'roleFieldAclIds' : 'roleTableAclIds'
}

function aclRefText(item: DanglingAclItem): string {
  if (item.kind === 'FIELD') return `列 ${item.refColumn ?? '—'}`
  return `表 ${item.refTable ?? '—'}`
}

function aclTargetText(item: DanglingAclItem): string {
  return item.target === 'USER' ? `成员#${item.principalId}` : `角色#${item.principalId}`
}

function aclEffectText(item: DanglingAclItem): string {
  if (item.kind === 'FIELD') return item.effect === 'UNMASK' ? '放行脱敏' : '强制脱敏'
  return item.effect === 'HIDDEN' ? '隐藏表' : '放行可见'
}

/** 一键清理：收集勾选项 → 二次确认 → 调 prune → 重新检测。 */
async function prune(): Promise<void> {
  if (!hasPrunableSelection.value || selectedDs.value == null) return
  const req: DriftPruneRequest = {
    datasourceId: selectedDs.value,
    semanticIds: selSemantic.value.map((x) => x.id),
    relationIds: selRelation.value.map((x) => x.id),
    userFieldAclIds: selAcl.value.filter((x) => aclTableOf(x) === 'userFieldAclIds').map((x) => x.id),
    userTableAclIds: selAcl.value.filter((x) => aclTableOf(x) === 'userTableAclIds').map((x) => x.id),
    roleFieldAclIds: selAcl.value.filter((x) => aclTableOf(x) === 'roleFieldAclIds').map((x) => x.id),
    roleTableAclIds: selAcl.value.filter((x) => aclTableOf(x) === 'roleTableAclIds').map((x) => x.id)
  }
  const count =
    (req.semanticIds?.length ?? 0) +
    (req.relationIds?.length ?? 0) +
    (req.userFieldAclIds?.length ?? 0) +
    (req.userTableAclIds?.length ?? 0) +
    (req.roleFieldAclIds?.length ?? 0) +
    (req.roleTableAclIds?.length ?? 0)
  try {
    await ElMessageBox.confirm(
      `确定清理 ${count} 条孤儿元数据吗？仅删除元数据、不动业务库，且不可撤销。`,
      '一键清理确认',
      { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await schemaDriftApi.prune(req)
    ElMessage.success(`清理完成：${JSON.stringify(res)}`)
    await runCheck()
  } catch {
    // 拦截器已提示
  }
}

/** 坏引用指标 → 跳转指标库修复 SQL。 */
function goFixMetric(m: BrokenMetric): void {
  router.push({ path: '/app/metrics', query: { id: String(m.id) } })
}

/** 受影响驾驶舱 → 跳转驾驶舱编辑器。 */
function goFixDashboard(d: AffectedDashboard): void {
  router.push(`/app/dashboards/${d.id}/edit`)
}
</script>

<template>
  <div class="drift-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">结构对账</h2>
        <p class="ph-desc">
          物理表改名 / 删列后，下游靠「表名+列名」引用的元数据会静默失配。此处直扫当前真实结构，
          一次性列出全部失配点，可一键清理孤儿、或跳转修复坏引用指标与驾驶舱。
        </p>
      </div>
    </div>

    <!-- 数据源选择 + 检测 -->
    <div class="toolbar card">
      <el-select
        v-model="selectedDs"
        placeholder="选择数据源"
        style="width: 320px"
        filterable
        clearable
        @change="report = null"
      >
        <el-option v-for="d in dsOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <PermGuard code="semantic:meta:read" mode="disable" tip="当前角色无结构对账权限">
        <el-button type="primary" :loading="checking" :disabled="selectedDs == null" @click="runCheck">
          <el-icon><Search /></el-icon> 开始检测
        </el-button>
      </PermGuard>
      <PermGuard code="semantic:meta:read" mode="disable" tip="当前角色无结构对账权限">
        <el-button type="warning" plain :loading="watching" :disabled="selectedDs == null" @click="runWatch">
          <el-icon><Refresh /></el-icon> 感知变更
        </el-button>
      </PermGuard>
      <PermGuard code="semantic:meta:delete" mode="disable" tip="当前角色无清理权限">
        <el-button
          type="danger"
          plain
          :disabled="!hasPrunableSelection"
          @click="prune"
        >
          <el-icon><Delete /></el-icon> 一键清理勾选项
        </el-button>
      </PermGuard>
      <span v-if="report" class="schema-stat">
        物理结构：{{ report.schemaTableCount }} 表 / {{ report.schemaColumnCount }} 列 ·
        失配 <b :class="{ zero: totalDrift === 0 }">{{ totalDrift }}</b> 处
      </span>
    </div>

    <el-empty v-if="!report && !checking" description="选择数据源后点「开始检测」" />
    <div v-else v-loading="checking" class="drift-body">
      <el-empty v-if="report && totalDrift === 0" description="未发现结构漂移，下游引用均与当前物理结构一致" />

      <template v-if="report && totalDrift > 0">
        <!-- ① 语义层孤儿行 -->
        <section v-if="report.orphanSemantics.length" class="drift-sec card">
          <header class="sec-head">
            <span class="sec-title">① 语义层孤儿行</span>
            <el-tag size="small" effect="plain" round>{{ report.orphanSemantics.length }}</el-tag>
            <span class="sec-hint">表/列已从物理结构消失，语义标注无处附着，勾选可删除。</span>
          </header>
          <el-table :data="report.orphanSemantics" size="small" border @selection-change="(r: any) => (selSemantic = r)">
            <el-table-column type="selection" width="44" />
            <el-table-column prop="tableName" label="表" min-width="130" />
            <el-table-column prop="columnName" label="列" min-width="130" />
            <el-table-column prop="aliasCn" label="别名" min-width="120" show-overflow-tooltip />
            <el-table-column label="失配类型" min-width="110">
              <template #default="{ row }">
                <el-tag v-if="row.tableMissing" size="small" type="danger" effect="light">表不存在</el-tag>
                <el-tag v-else size="small" type="warning" effect="light">列不存在</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
          </el-table>
        </section>

        <!-- ② 悬空关联 -->
        <section v-if="report.danglingRelations.length" class="drift-sec card">
          <header class="sec-head">
            <span class="sec-title">② 悬空关联登记</span>
            <el-tag size="small" effect="plain" round>{{ report.danglingRelations.length }}</el-tag>
            <span class="sec-hint">JOIN 口径某侧表/列已不存在，多表问数会被误导，勾选可删除。</span>
          </header>
          <el-table :data="report.danglingRelations" size="small" border @selection-change="(r: any) => (selRelation = r)">
            <el-table-column type="selection" width="44" />
            <el-table-column label="左表.列" min-width="180">
              <template #default="{ row }">{{ row.leftTable }}<template v-if="row.leftColumn">.{{ row.leftColumn }}</template></template>
            </el-table-column>
            <el-table-column label="右表.列" min-width="180">
              <template #default="{ row }">{{ row.rightTable }}<template v-if="row.rightColumn">.{{ row.rightColumn }}</template></template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
          </el-table>
        </section>

        <!-- ③ 悬空权限 -->
        <section v-if="report.danglingAcls.length" class="drift-sec card">
          <header class="sec-head">
            <span class="sec-title">③ 悬空字段/表级权限</span>
            <el-tag size="small" effect="plain" round>{{ report.danglingAcls.length }}</el-tag>
            <span class="sec-hint">引用了已消失的表/列，脱敏/隐藏策略实际已失效（fail-open），勾选可删除。</span>
          </header>
          <el-table :data="report.danglingAcls" size="small" border @selection-change="(r: any) => (selAcl = r)">
            <el-table-column type="selection" width="44" />
            <el-table-column label="主体" min-width="130">
              <template #default="{ row }">{{ aclTargetText(row) }}</template>
            </el-table-column>
            <el-table-column label="维度" min-width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.kind === 'FIELD' ? '字段脱敏' : '表级可见性' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="引用" min-width="150">
              <template #default="{ row }">{{ aclRefText(row) }}</template>
            </el-table-column>
            <el-table-column label="策略" min-width="100">
              <template #default="{ row }">{{ aclEffectText(row) }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.kind === 'FIELD' ? `列「${row.refColumn}」已不存在` : `表「${row.refTable}」已不存在` }}
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ④ 坏引用指标（只读 + 跳转修复） -->
        <section v-if="report.brokenMetrics.length" class="drift-sec card">
          <header class="sec-head">
            <span class="sec-title">④ 坏引用指标</span>
            <el-tag size="small" effect="plain" round>{{ report.brokenMetrics.length }}</el-tag>
            <span class="sec-hint">SQL / 图表字段引用了不存在的表或列，刷新会报错，请前往指标库修复。</span>
          </header>
          <el-table :data="report.brokenMetrics" size="small" border>
            <el-table-column prop="name" label="指标" min-width="160" show-overflow-tooltip />
            <el-table-column label="坏引用" min-width="280">
              <template #default="{ row }">
                <el-tag
                  v-for="(ref, i) in row.refs"
                  :key="i"
                  size="small"
                  :type="ref.tableMissing ? 'danger' : 'warning'"
                  effect="light"
                  class="ref-tag"
                >
                  {{ ref.tableMissing ? `表 ${ref.table}` : `${ref.table}.${ref.column}` }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="goFixMetric(row)">去修复</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ⑤ 受影响驾驶舱（只读 + 跳转修复） -->
        <section v-if="report.affectedDashboards.length" class="drift-sec card">
          <header class="sec-head">
            <span class="sec-title">⑤ 受影响驾驶舱</span>
            <el-tag size="small" effect="plain" round>{{ report.affectedDashboards.length }}</el-tag>
            <span class="sec-hint">widget 引用了坏引用或已删除的指标，图表会空白，请前往驾驶舱编辑器重选。</span>
          </header>
          <el-table :data="report.affectedDashboards" size="small" border>
            <el-table-column prop="name" label="驾驶舱" min-width="180" show-overflow-tooltip />
            <el-table-column label="坏引用指标" min-width="120">
              <template #default="{ row }">
                <el-tag v-if="row.brokenMetricIds.length" size="small" type="warning" effect="light">
                  {{ row.brokenMetricIds.length }} 个坏引用
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="缺失指标" min-width="120">
              <template #default="{ row }">
                <el-tag v-if="row.missingMetricIds.length" size="small" type="danger" effect="light">
                  {{ row.missingMetricIds.length }} 个已删除
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="goFixDashboard(row)">去修复</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </template>
    </div>

    <!-- T12-3 感知变更结果：变更摘要 + 受影响资产（已同步台账/站内信） -->
    <el-dialog v-model="watchDialog" title="结构变更感知结果" width="640px">
      <template v-if="watchRes">
        <p class="sec-hint" style="margin: 0 0 10px">
          {{ watchRes.note ?? '发现删除类变更并砸到既有资产，已入台账并定向站内信资产创建人。台账入口见「待治理问题」面板。' }}
          <template v-if="watchRes.notifiedUsers > 0">（本次推送 {{ watchRes.notifiedUsers }} 人）</template>
        </p>
        <el-descriptions :column="1" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="变更明细">
            <div v-for="(c, i) in watchRes.changeSummaries" :key="i">{{ c }}</div>
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="watchRes.impacts" size="small" border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ assetKindText(row.assetType) }}</template>
          </el-table-column>
          <el-table-column prop="name" label="资产" min-width="140" show-overflow-tooltip />
          <el-table-column label="被打断引用" min-width="240">
            <template #default="{ row }">
              <el-tag v-for="(ref, i) in row.brokenRefs" :key="i" size="small" type="warning" effect="light" class="ref-tag">
                {{ ref }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.drift-view {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 32px;
}
.page-head {
  margin-bottom: 18px;
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
  max-width: 760px;
  line-height: 1.6;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.schema-stat {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-secondary);
}
.schema-stat b {
  color: var(--el-color-danger);
  font-size: 15px;
}
.schema-stat b.zero {
  color: var(--success);
}
.drift-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 120px;
}
.drift-sec {
  padding: 14px 16px 16px;
}
.sec-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.sec-hint {
  font-size: 12px;
  color: var(--text-secondary);
}
.ref-tag {
  margin: 0 4px 4px 0;
}
.muted {
  color: var(--text-secondary);
}
</style>
