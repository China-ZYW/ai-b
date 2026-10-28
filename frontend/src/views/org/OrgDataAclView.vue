<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataAclApi } from '@/api/orgDataAcl'
import { orgCustomRoleApi } from '@/api/orgCustomRole'
import { orgMemberApi } from '@/api/orgMember'
import { datasourceApi } from '@/api/datasource'
import { semanticApi } from '@/api/semantic'
import { usePermissionStore } from '@/stores/permission'
import { useActionGuard } from '@/utils/actionGuard'
import type {
  CustomRoleVO,
  DataAclItemVO,
  DataSourceVO,
  MemberVO,
  SemanticMeta,
  TableMeta
} from '@/types'

/**
 * 数据授权（P2 数据权限 A，《组织治理专项设计》§3）。
 *
 * 组织管理员在此为「成员直授」或「自定义角色继承」配置三类数据权限：
 *  - **数据源可见性**：HIDDEN（隐藏，问数时从 Prompt schema 中省略并可被前置拦截）/ ALLOW（显式放行）；无行=可见。
 *  - **表级可见性**：HIDDEN（该表从 schema 省略，且执行前 JSqlParser 解析出命中它的 SQL 会被拦截）/ ALLOW（显式放行）；无行=可见。
 *  - **字段脱敏**：UNMASK（显式放行，价格等敏感列对该主体可见）/ MASK（显式掩码）；无行=按语义层默认掩码（fail-closed）。
 *
 * 合并解析「取最宽」（成员直授 ∪ 角色继承；ALLOW 胜 HIDDEN、UNMASK 胜 MASK）与脱敏施加
 * 由后端 `DataMaskGate` 在问数 / 指标 / 驾驶舱 / 归因出口统一完成；本页只做配置与呈现。
 *
 * 权限码 org:customrole:write（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；租户范围由后端强制收敛。
 */
const perm = usePermissionStore()
const canWrite = computed(() => perm.has('org:customrole:write'))

const loading = ref(false)
const members = ref<MemberVO[]>([])
const roles = ref<CustomRoleVO[]>([])
const datasources = ref<DataSourceVO[]>([])
const columns = ref<SemanticMeta[]>([])
/** 选中数据源下的物理表（表级可见性用；来自 datasource/schema，即「扫到了哪些表」的权威来源）。 */
const tables = ref<TableMeta[]>([])
const aclItems = ref<DataAclItemVO[]>([])

/* 授权对象（成员直授 / 角色继承）与数据源 */
const targetType = ref<'USER' | 'ROLE'>('USER')
const targetUid = ref<number | null>(null)
const targetRoleId = ref<number | null>(null)
const datasourceId = ref<number | null>(null)
/** 表级可见性当前选中的表名。 */
const tableName = ref<string | null>(null)
/** 字段脱敏区当前选中的表名（表筛选器）；null=全部表（按「表.列」平铺，点击仍按行所在表精确落库）。 */
const fieldTableName = ref<string | null>(null)

const { busy, run } = useActionGuard()

const targetId = computed<number | null>(() =>
  targetType.value === 'USER' ? targetUid.value : targetRoleId.value
)
const targetReady = computed(() => targetId.value != null)
const dsReady = computed(() => datasourceId.value != null)

function memberLabel(m: MemberVO): string {
  return `${m.nickname || m.username || '#' + m.uid}（${m.username || m.uid}）`
}
function roleLabel(r: CustomRoleVO): string {
  return `${r.name}（${r.baseRoleCode}）`
}
function dsName(id: number): string {
  return datasources.value.find((d) => d.id === id)?.name || `#${id}`
}
function colLabel(s: SemanticMeta): string {
  return s.tableName ? `${s.tableName}.${s.columnName}` : String(s.columnName || '')
}

/** 字段脱敏候选列：按当前选中的表筛选（null=全部表）。 */
const filteredColumns = computed<SemanticMeta[]>(() =>
  fieldTableName.value == null
    ? columns.value
    : columns.value.filter((c) => (c.tableName || null) === fieldTableName.value)
)

/* ---------------- 加载 ---------------- */

async function loadBase(): Promise<void> {
  loading.value = true
  try {
    const [ms, rs, ds] = await Promise.all([
      orgMemberApi.list().catch(() => [] as MemberVO[]),
      orgCustomRoleApi.list().catch(() => [] as CustomRoleVO[]),
      datasourceApi.list().catch(() => [] as DataSourceVO[])
    ])
    members.value = ms ?? []
    roles.value = rs ?? []
    datasources.value = ds ?? []
  } finally {
    loading.value = false
  }
}

async function loadAcl(): Promise<void> {
  if (targetId.value == null) {
    aclItems.value = []
    return
  }
  try {
    aclItems.value =
      targetType.value === 'USER'
        ? await dataAclApi.listUser(targetId.value)
        : await dataAclApi.listRole(targetId.value)
  } catch {
    aclItems.value = []
  }
}

/**
 * 字段脱敏的候选列 = 语义登记列 ∪ 物理 schema 列（按「表.列」去重，语义行优先保留其别名/敏感标记）。
 * 只吃语义行会漏掉"已同步但还没补语义"的表——那些列恰恰最需要显式放行或掩码，
 * 否则管理员会以为没有列可配，掩码策略漏配后敏感列直接明文出数。
 * 后端字段级授权本身按 columnName 匹配，故并入同名列不会改变生效口径。
 */
async function loadColumns(): Promise<void> {
  if (datasourceId.value == null) {
    columns.value = []
    return
  }
  try {
    const [semantics, schema] = await Promise.all([
      semanticApi.list(datasourceId.value).catch(() => [] as SemanticMeta[]),
      datasourceApi.schema(datasourceId.value).catch(() => [] as TableMeta[])
    ])
    const merged = new Map<string, SemanticMeta>()
    ;(semantics || [])
      .filter((s) => s.objectType === 'COLUMN' && !!s.columnName)
      .forEach((s) => {
        const key = `${s.tableName || ''}.${s.columnName}`
        if (!merged.has(key)) merged.set(key, s)
      })
    ;(schema || []).forEach((t) => {
      ;(t.columns ?? []).forEach((c) => {
        if (!c.name) return
        const key = `${t.name || ''}.${c.name}`
        if (merged.has(key)) return
        merged.set(key, { tableName: t.name, columnName: c.name, aliasCn: '' })
      })
    })
    columns.value = Array.from(merged.values())
  } catch {
    columns.value = []
  }
}

/** 表清单取「扫到的物理表」：表级可见性决定问数时能否看见/用这张表。 */
async function loadTables(): Promise<void> {
  if (datasourceId.value == null) {
    tables.value = []
    tableName.value = null
    return
  }
  try {
    const all = await datasourceApi.schema(datasourceId.value)
    tables.value = (all || []).filter((t) => !!t.name)
    if (tableName.value && !tables.value.some((t) => t.name === tableName.value)) {
      tableName.value = null // 选中的表可能已被删/改名
    }
  } catch {
    tables.value = []
  }
}

onMounted(async () => {
  await loadBase()
  if (datasources.value.length && datasourceId.value == null) {
    datasourceId.value = datasources.value[0].id
    await loadColumns()
    await loadTables()
  }
})

async function onTargetTypeChange(): Promise<void> {
  await loadAcl()
}
async function onTargetChange(): Promise<void> {
  await loadAcl()
}
async function onDatasourceChange(): Promise<void> {
  tableName.value = null
  fieldTableName.value = null
  await loadColumns()
  await loadTables()
}

/* ---------------- 当前生效状态（针对选中的数据源） ---------------- */

const dsEffect = computed<string | null>(() => {
  const it = aclItems.value.find((a) => a.kind === 'DATASOURCE' && a.datasourceId === datasourceId.value)
  return it ? it.effect : null
})

function fieldEffect(col: string, tbl?: string | null): string | null {
  const t = tbl ?? null
  const it = aclItems.value.find(
    (a) => a.kind === 'FIELD' && a.datasourceId === datasourceId.value && a.columnName === col && (a.tableName || null) === t
  )
  return it ? it.effect : null
}

function dsEffectLabel(e: string | null): string {
  if (e === 'HIDDEN') return '已隐藏'
  if (e === 'ALLOW') return '已放行'
  return '默认可见'
}
function dsEffectTag(e: string | null): 'danger' | 'success' | 'info' {
  if (e === 'HIDDEN') return 'danger'
  if (e === 'ALLOW') return 'success'
  return 'info'
}
function fieldEffectLabel(e: string | null): string {
  if (e === 'UNMASK') return '已放行'
  if (e === 'MASK') return '显式掩码'
  return '按语义层默认'
}
function fieldEffectTag(e: string | null): 'success' | 'warning' | 'info' {
  if (e === 'UNMASK') return 'success'
  if (e === 'MASK') return 'warning'
  return 'info'
}

/** 已配置授权的三种维度展示（TABLE 维与数据源同用 HIDDEN/ALLOW）。 */
function kindLabel(k: string): string {
  return k === 'DATASOURCE' ? '数据源可见性' : k === 'FIELD' ? '字段脱敏' : '表级可见性'
}

/** 已配置授权「对象」列展示：字段脱敏优先显示「表.列」（无表名时退化为全局列名）。 */
function aclObjectLabel(row: DataAclItemVO): string {
  if (row.kind === 'TABLE') return row.tableName || '—'
  if (row.kind === 'FIELD') return row.tableName ? `${row.tableName}.${row.columnName}` : (row.columnName || '—')
  return '—（整源）'
}
function kindTag(k: string): 'primary' | 'warning' | 'success' {
  return k === 'DATASOURCE' ? 'primary' : k === 'FIELD' ? 'warning' : 'success'
}

/* ---------------- 写动作 ---------------- */

async function setDs(effect: 'HIDDEN' | 'ALLOW'): Promise<void> {
  if (!targetReady.value || !dsReady.value) {
    ElMessage.warning('请先选择授权对象与数据源')
    return
  }
  await run(async () => {
    await dataAclApi.set({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!,
      effect
    })
    ElMessage.success(effect === 'HIDDEN' ? '已设为隐藏（问数时该数据源对主体不可见）' : '已设为显式放行')
    await loadAcl()
  })
}

async function clearDs(): Promise<void> {
  if (!targetReady.value || !dsReady.value) return
  try {
    await ElMessageBox.confirm('清除后该数据源恢复「默认可见」。确定清除吗？', '清除数据源可见性', {
      type: 'warning',
      confirmButtonText: '清除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await run(async () => {
    await dataAclApi.clear({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!
    })
    ElMessage.success('已清除（恢复默认可见）')
    await loadAcl()
  })
}

async function setField(col: string, effect: 'MASK' | 'UNMASK', tbl?: string | null): Promise<void> {
  if (!targetReady.value || !dsReady.value) {
    ElMessage.warning('请先选择授权对象与数据源')
    return
  }
  await run(async () => {
    await dataAclApi.set({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!,
      tableName: tbl ?? null,
      columnName: col,
      effect
    })
    const scope = tbl ? `「${tbl}.${col}」` : `「${col}」（全局同名列）`
    ElMessage.success(effect === 'UNMASK' ? `已放行${scope}` : `已对${scope}设显式掩码`)
    await loadAcl()
  })
}

async function clearField(col: string, tbl?: string | null): Promise<void> {
  if (!targetReady.value || !dsReady.value) return
  await run(async () => {
    await dataAclApi.clear({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!,
      tableName: tbl ?? null,
      columnName: col
    })
    ElMessage.success('已清除（恢复语义层默认）')
    await loadAcl()
  })
}

/* ---------------- 表级可见性 ---------------- */

function tableEffect(t: string): string | null {
  const it = aclItems.value.find(
    (a) => a.kind === 'TABLE' && a.datasourceId === datasourceId.value && a.tableName === t
  )
  return it ? it.effect : null
}

function tableEffectLabel(e: string | null): string {
  if (e === 'HIDDEN') return '已隐藏'
  if (e === 'ALLOW') return '已放行'
  return '默认可见'
}
function tableEffectTag(e: string | null): 'danger' | 'success' | 'info' {
  if (e === 'HIDDEN') return 'danger'
  if (e === 'ALLOW') return 'success'
  return 'info'
}
function tableLabel(t: TableMeta): string {
  return t.comment ? `${t.name}（${t.comment}）` : t.name
}

async function setTable(t: string, effect: 'HIDDEN' | 'ALLOW'): Promise<void> {
  if (!targetReady.value || !dsReady.value) {
    ElMessage.warning('请先选择授权对象与数据源')
    return
  }
  await run(async () => {
    await dataAclApi.set({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!,
      tableName: t,
      effect
    })
    ElMessage.success(
      effect === 'HIDDEN'
        ? `已隐藏「${t}」：问数不再可见，手填/生成的 SQL 命中该表也会被拦截`
        : `已放行「${t}」`
    )
    await loadAcl()
  })
}

async function clearTable(t: string): Promise<void> {
  if (!targetReady.value || !dsReady.value) return
  await run(async () => {
    await dataAclApi.clear({
      targetType: targetType.value,
      targetId: targetId.value!,
      datasourceId: datasourceId.value!,
      tableName: t
    })
    ElMessage.success('已清除（恢复默认可见）')
    await loadAcl()
  })
}
</script>

<template>
  <div class="data-acl-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">数据授权</h2>
        <p class="ph-desc">
          为成员或自定义角色配置「数据源可见性」「表级可见性」与「字段脱敏」。成员直授与角色继承<b>合并取最宽</b>；
          未被显式放行的敏感列默认掩码（fail-closed）。配置在问数 / 指标 / 驾驶舱 / 归因出口统一生效。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="loadBase"><el-icon><Refresh /></el-icon> 刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="配置说明"
      class="hint-alert"
    >
      <template #default>
        <b>数据源可见性</b>：HIDDEN=隐藏（问数时该数据源连 schema 都不进 Prompt，并在消耗配额前拦截）；ALLOW=显式放行；无记录=可见。<br />
        <b>表级可见性</b>：在数据源之上再收窄到单表。HIDDEN=该表从 schema 中省略，且手填/模型生成的 SQL 一旦命中它会被前置拦截（抛「无权访问该数据表」）；无记录=可见。<br />
        <b>字段脱敏</b>：UNMASK=显式放行该列（价格等敏感列可见）；MASK=显式掩码；无记录=按语义层 sensitive 默认掩码。
      </template>
    </el-alert>

    <!-- ============ 配置区 ============ -->
    <div class="card body-card">
      <div class="cfg-row">
        <div class="cfg-item">
          <span class="cfg-label">授权对象</span>
          <el-radio-group v-model="targetType" :disabled="busy" @change="onTargetTypeChange">
            <el-radio-button value="USER">成员直授</el-radio-button>
            <el-radio-button value="ROLE">角色继承</el-radio-button>
          </el-radio-group>
        </div>

        <div class="cfg-item">
          <span class="cfg-label">{{ targetType === 'USER' ? '选择成员' : '选择角色' }}</span>
          <el-select
            v-if="targetType === 'USER'"
            v-model="targetUid"
            filterable
            clearable
            placeholder="选择成员"
            style="width: 280px"
            @change="onTargetChange"
          >
            <el-option v-for="m in members" :key="m.uid" :label="memberLabel(m)" :value="m.uid" />
          </el-select>
          <el-select
            v-else
            v-model="targetRoleId"
            filterable
            clearable
            placeholder="选择自定义角色"
            style="width: 280px"
            @change="onTargetChange"
          >
            <el-option v-for="r in roles" :key="r.id" :label="roleLabel(r)" :value="r.id" />
          </el-select>
          <span v-if="targetType === 'ROLE' && !roles.length" class="dim cfg-empty">（暂无自定义角色，可先在「角色权限」中创建）</span>
        </div>

        <div class="cfg-item">
          <span class="cfg-label">数据源</span>
          <el-select
            v-model="datasourceId"
            filterable
            placeholder="选择数据源"
            style="width: 280px"
            @change="onDatasourceChange"
          >
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </div>
      </div>

      <!-- 数据源可见性 -->
      <div class="sec">
        <div class="sec-head">
          <span class="sec-title">数据源可见性</span>
          <el-tag :type="dsEffectTag(dsEffect)" effect="light" round size="small">{{ dsEffectLabel(dsEffect) }}</el-tag>
        </div>
        <div class="sec-body">
          <el-button
            type="danger"
            plain
            :disabled="!canWrite || !targetReady || !dsReady || dsEffect === 'HIDDEN'"
            :loading="busy"
            @click="setDs('HIDDEN')"
          >
            <el-icon><Hide /></el-icon> 设为隐藏
          </el-button>
          <el-button
            type="success"
            plain
            :disabled="!canWrite || !targetReady || !dsReady || dsEffect === 'ALLOW'"
            :loading="busy"
            @click="setDs('ALLOW')"
          >
            <el-icon><View /></el-icon> 设为放行
          </el-button>
          <el-button
            :disabled="!canWrite || !targetReady || !dsReady || dsEffect == null"
            :loading="busy"
            @click="clearDs"
          >
            清除
          </el-button>
          <span v-if="!targetReady" class="dim sec-empty">请先选择授权对象</span>
        </div>
      </div>

      <!-- 表级可见性 -->
      <div class="sec">
        <div class="sec-head">
          <span class="sec-title">表级可见性</span>
          <span class="dim">共 {{ tables.length }} 张表；隐藏后问数看不到该表，命中它的 SQL 也会被拦截</span>
        </div>
        <div class="sec-body">
          <el-select
            v-model="tableName"
            filterable
            clearable
            placeholder="选择数据表"
            style="width: 320px"
          >
            <el-option v-for="t in tables" :key="t.name" :label="tableLabel(t)" :value="t.name" />
          </el-select>
          <el-tag
            :type="tableEffectTag(tableEffect(tableName || ''))"
            effect="light"
            round
            size="small"
          >
            {{ tableEffectLabel(tableEffect(tableName || '')) }}
          </el-tag>
          <el-button
            type="danger"
            plain
            :disabled="!canWrite || !targetReady || !dsReady || !tableName || tableEffect(tableName!) === 'HIDDEN'"
            :loading="busy"
            @click="setTable(tableName!, 'HIDDEN')"
          >
            <el-icon><Hide /></el-icon> 隐藏
          </el-button>
          <el-button
            type="success"
            plain
            :disabled="!canWrite || !targetReady || !dsReady || !tableName || tableEffect(tableName!) === 'ALLOW'"
            :loading="busy"
            @click="setTable(tableName!, 'ALLOW')"
          >
            <el-icon><View /></el-icon> 放行
          </el-button>
          <el-button
            :disabled="!canWrite || !targetReady || !dsReady || !tableName || tableEffect(tableName!) == null"
            :loading="busy"
            @click="clearTable(tableName!)"
          >
            清除
          </el-button>
          <span v-if="!targetReady" class="dim sec-empty">请先选择授权对象</span>
          <span v-else-if="!tables.length" class="dim sec-empty">该数据源暂无表，可到数据源管理点「同步」重扫</span>
        </div>
      </div>

      <!-- 字段脱敏 -->
      <div class="sec">
        <div class="sec-head">
          <span class="sec-title">字段脱敏</span>
          <el-select
            v-model="fieldTableName"
            filterable
            clearable
            placeholder="按表筛选（留空=全部表）"
            style="width: 280px"
          >
            <el-option v-for="t in tables" :key="t.name" :label="tableLabel(t)" :value="t.name" />
          </el-select>
          <span class="dim">共 {{ filteredColumns.length }} 列；带「敏感」标记的列未放行时默认掩码；可精确到「表.列」</span>
        </div>
        <el-table v-loading="loading" :data="filteredColumns" max-height="420" size="small" border stripe>
          <el-table-column label="字段" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ colLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="aliasCn" label="业务别名" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.aliasCn || '—' }}</template>
          </el-table-column>
          <el-table-column label="敏感" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.sensitive === 1" type="warning" size="small" effect="light" round>敏感</el-tag>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="当前效果" width="140" align="center">
            <template #default="{ row }">
              <el-tag :type="fieldEffectTag(fieldEffect(row.columnName, row.tableName))" size="small" effect="light" round>
                {{ fieldEffectLabel(fieldEffect(row.columnName, row.tableName)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right" align="center">
            <template #default="{ row }">
              <el-button
                type="success"
                link
                size="small"
                :disabled="!canWrite || !targetReady || fieldEffect(row.columnName, row.tableName) === 'UNMASK'"
                @click="setField(row.columnName, 'UNMASK', row.tableName)"
              >
                放行
              </el-button>
              <el-button
                type="warning"
                link
                size="small"
                :disabled="!canWrite || !targetReady || fieldEffect(row.columnName, row.tableName) === 'MASK'"
                @click="setField(row.columnName, 'MASK', row.tableName)"
              >
                掩码
              </el-button>
              <el-button
                link
                size="small"
                :disabled="!canWrite || !targetReady || fieldEffect(row.columnName, row.tableName) == null"
                @click="clearField(row.columnName, row.tableName)"
              >
                清除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="该数据源暂无列（可到数据源管理点「同步」重扫表结构）" :image-size="80" />
          </template>
        </el-table>
      </div>
    </div>

    <!-- ============ 已配置授权 ============ -->
    <div class="card body-card">
      <div class="sec-head">
        <span class="sec-title">已配置授权</span>
        <span class="dim">共 {{ aclItems.length }} 条</span>
      </div>
      <el-table :data="aclItems" max-height="360" size="small" border stripe>
        <el-table-column label="种类" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="kindTag(row.kind)" size="small" effect="light" round>
              {{ kindLabel(row.kind) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据源" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ dsName(row.datasourceId) }}</template>
        </el-table-column>
        <el-table-column label="对象" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ aclObjectLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="效果" width="140" align="center">
          <template #default="{ row }">
            <el-tag :type="row.effect === 'HIDDEN' || row.effect === 'MASK' ? 'danger' : 'success'" size="small" effect="light" round>
              {{ row.effect === 'HIDDEN' ? '隐藏' : row.effect === 'ALLOW' ? '放行' : row.effect === 'UNMASK' ? '放行' : '掩码' }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="targetReady ? '该对象暂无数据授权配置' : '请先选择授权对象'" :image-size="80" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.data-acl-view {
  height: 100%;
  overflow-y: auto;
  padding: 4px 4px 40px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
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
  max-width: 900px;
}
.hint-alert {
  margin-bottom: 14px;
}
.body-card {
  margin-bottom: 16px;
  padding: 16px;
}
.cfg-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px 32px;
  align-items: center;
  margin-bottom: 8px;
}
.cfg-item {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cfg-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.cfg-empty {
  font-size: 12px;
}
.sec {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.sec-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.sec-title {
  font-size: 14px;
  font-weight: 700;
}
.sec-body {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sec-empty {
  font-size: 12px;
  margin-left: 4px;
}
.dim {
  color: var(--text-secondary);
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
}
</style>
