<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgCustomRoleApi } from '@/api/orgCustomRole'
import { usePermissionStore } from '@/stores/permission'
import { useActionGuard } from '@/utils/actionGuard'
import type { CustomRoleVO } from '@/types'

/**
 * 角色权限（P1 组织角色可编辑，《组织治理专项设计》§2）。
 *
 * 组织管理员创建/管理**本组织自定义角色**：派生自一个可授予的内置组织角色（base，不可为 OWNER），
 * 有效权限 = `base − 收窄项`（DENY 优先、不可反转）——即只能比 base 更窄，不能更宽（后端硬拒 GRANT 超出 base）。
 *
 * 内置角色字典只读（由平台 `RbacSeeder` 维护），本页**不编辑内置角色**，只能新建租户内自定义角色。
 * 权限码 org:customrole:write（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；租户范围由后端强制收敛。
 */
const perm = usePermissionStore()
const canWrite = computed(() => perm.has('org:customrole:write'))

/** 可授予的内置组织角色（与后端 OrgRole 对齐；OWNER 属 NON_GRANTABLE，不可作 base）。 */
const BASE_ROLES = [
  { code: 'ADMIN', label: '组织管理员' },
  { code: 'DATA_STEWARD', label: '数据管理员' },
  { code: 'ANALYST', label: '分析师' },
  { code: 'VIEWER', label: '只读成员' },
  { code: 'BILLING', label: '计费联系人' }
]
/** 数据范围（排除跨租户 ALL；v1 实际收敛仍按 base，此处仅登记）。 */
const DATA_SCOPES = [
  { value: 'TENANT', label: '组织内全部' },
  { value: 'TEAM_AND_CHILD', label: '本团队及子团队' },
  { value: 'TEAM', label: '仅本团队' },
  { value: 'SELF', label: '仅本人' },
  { value: 'CUSTOM', label: '逐资源授权' }
]

/** 权限码模块前缀 → 中文标签（后端无组织侧权限字典接口，前端仅作展示）。 */
const MODULE_LABELS: Record<string, string> = {
  chat: '对话问数', datasource: '数据源', metric: '指标库', dashboard: '驾驶舱',
  semantic: '语义层', config: '配置中心', member: '成员与组织', org: '组织设置',
  audit: '审计', alert: '智能预警', share: '分享', history: '查询历史', favorite: '收藏',
  tuning: '问数调优', profile: '个人中心', manual: '用户手册', theme: '主题',
  lineage: '数据血缘', attribution: '归因', quota: '配额'
}
const ACTION_LABELS: Record<string, string> = {
  read: '查看', write: '编辑', delete: '删除', assign: '分配', export: '导出', create: '新建'
}

function baseLabel(code?: string | null): string {
  return BASE_ROLES.find((r) => r.code === code)?.label || (code || '—')
}
function scopeLabel(v?: string | null): string {
  return DATA_SCOPES.find((s) => s.value === v)?.label || (v || '—')
}
function moduleOf(code: string): string {
  return code.split(':')[0] || 'other'
}
function permLabel(code: string): string {
  const parts = code.split(':')
  const module = MODULE_LABELS[parts[0]] || parts[0]
  const action = ACTION_LABELS[parts[parts.length - 1]] || parts[parts.length - 1]
  return `${module} · ${action}`
}

const loading = ref(false)
const roles = ref<CustomRoleVO[]>([])
const { busy, run } = useActionGuard()

async function load(): Promise<void> {
  loading.value = true
  try {
    roles.value = (await orgCustomRoleApi.list()) ?? []
  } catch {
    roles.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ---------------- 新建 / 编辑 ---------------- */
const editVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<{
  name: string
  baseRoleCode: string
  dataScope: string
  description: string
  enabled: boolean
}>({ name: '', baseRoleCode: 'ANALYST', dataScope: 'TEAM', description: '', enabled: true })

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.baseRoleCode = 'ANALYST'
  form.dataScope = 'TEAM'
  form.description = ''
  form.enabled = true
  editVisible.value = true
}
function openEdit(r: CustomRoleVO): void {
  editingId.value = r.id
  form.name = r.name
  form.baseRoleCode = r.baseRoleCode
  form.dataScope = r.dataScope || 'TEAM'
  form.description = r.description || ''
  form.enabled = r.enabled !== false && r.enabled !== 0
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  await run(async () => {
    if (editingId.value == null) {
      await orgCustomRoleApi.create({
        name: form.name.trim(),
        baseRoleCode: form.baseRoleCode,
        dataScope: form.dataScope,
        description: form.description,
        enabled: form.enabled ? 1 : 0
      })
      ElMessage.success('已创建自定义角色')
    } else {
      await orgCustomRoleApi.update(editingId.value, {
        name: form.name.trim(),
        description: form.description,
        dataScope: form.dataScope,
        enabled: form.enabled ? 1 : 0
      })
      ElMessage.success('已保存')
    }
    editVisible.value = false
    await load()
  })
}

async function toggleEnabled(r: CustomRoleVO, v: string | number | boolean): Promise<void> {
  const next = Boolean(v)
  await run(async () => {
    await orgCustomRoleApi.update(r.id, { enabled: next ? 1 : 0 })
    r.enabled = next
    ElMessage.success(next ? '已启用' : '已停用')
  })
}

async function onRemove(r: CustomRoleVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除自定义角色「${r.name}」吗？删除后不可恢复（仍有成员持有时会被拒绝）。`,
      '删除角色',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await run(async () => {
    await orgCustomRoleApi.remove(r.id)
    ElMessage.success('已删除')
    await load()
  })
}

/* ---------------- 权限设置 ---------------- */
const permVisible = ref(false)
const permTarget = ref<CustomRoleVO | null>(null)
const permLoading = ref(false)
const basePerms = ref<string[]>([])
const grantedSet = ref<string[]>([])
const checkedCodes = ref<string[]>([])

async function openPerm(r: CustomRoleVO): Promise<void> {
  permTarget.value = r
  permVisible.value = true
  permLoading.value = true
  try {
    const eff = await orgCustomRoleApi.effective(r.id)
    const base = [...(eff.basePermissions || [])].sort()
    basePerms.value = base
    grantedSet.value = [...(eff.granted || [])]
    const denied = new Set(eff.denied || [])
    checkedCodes.value = base.filter((c) => !denied.has(c))
  } catch {
    basePerms.value = []
    grantedSet.value = []
    checkedCodes.value = []
  } finally {
    permLoading.value = false
  }
}

const permGroups = computed(() => {
  const byModule = new Map<string, string[]>()
  for (const c of basePerms.value) {
    const m = moduleOf(c)
    if (!byModule.has(m)) byModule.set(m, [])
    byModule.get(m)!.push(c)
  }
  return [...byModule.entries()].map(([m, codes]) => ({ module: m, label: MODULE_LABELS[m] || m, codes }))
})

const deniedCount = computed(() => basePerms.value.filter((c) => !checkedCodes.value.includes(c)).length)

function isChecked(code: string): boolean {
  return checkedCodes.value.includes(code)
}
function toggleCode(code: string, v: boolean): void {
  if (v) {
    if (!checkedCodes.value.includes(code)) checkedCodes.value = [...checkedCodes.value, code]
  } else {
    checkedCodes.value = checkedCodes.value.filter((c) => c !== code)
  }
}
function checkAll(): void {
  checkedCodes.value = [...basePerms.value]
}
function uncheckAll(): void {
  checkedCodes.value = []
}

async function submitPerm(): Promise<void> {
  if (permTarget.value == null) return
  const checked = new Set(checkedCodes.value)
  // grant：仅保留仍被勾选的历史 GRANT（必在 base 内）；deny：base 中未勾选者（收窄）
  const grant = grantedSet.value.filter((c) => checked.has(c))
  const deny = basePerms.value.filter((c) => !checked.has(c))
  await run(async () => {
    await orgCustomRoleApi.setPermissions(permTarget.value!.id, { grant, deny })
    ElMessage.success('权限已保存')
    permVisible.value = false
    await load()
  })
}
</script>

<template>
  <div class="custom-role-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">角色权限</h2>
        <p class="ph-desc">
          创建/管理本组织自定义角色。自定义角色派生自一个内置组织角色（base），
          <b>只能比 base 更窄、不能更宽</b>：有效权限 = base − 收窄项。保存后对持有该角色的成员即时生效。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button v-if="canWrite" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建角色
        </el-button>
      </div>
    </div>

    <div class="card body-card">
      <el-table v-loading="loading" :data="roles" size="small" border stripe>
        <el-table-column prop="name" label="角色名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="基于内置角色" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="light" round>{{ baseLabel(row.baseRoleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" width="140">
          <template #default="{ row }">{{ scopeLabel(row.dataScope) }}</template>
        </el-table-column>
        <el-table-column label="收窄项" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overrideCount > 0" type="warning" size="small" effect="light" round>
              {{ row.overrideCount }}
            </el-tag>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false && row.enabled !== 0"
              :loading="busy"
              :disabled="!canWrite || busy"
              @change="(v: string | number | boolean) => toggleEnabled(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :disabled="!canWrite" @click="openPerm(row)">
              权限设置
            </el-button>
            <el-button link size="small" :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
            <el-button type="danger" link size="small" :disabled="!canWrite" @click="onRemove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无自定义角色，点击右上「新建角色」创建" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- ============ 新建 / 编辑 ============ -->
    <el-dialog
      v-model="editVisible"
      :title="editingId == null ? '新建自定义角色' : '编辑角色'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：华东区分析师" />
        </el-form-item>
        <el-form-item label="基于角色">
          <el-select v-model="form.baseRoleCode" :disabled="editingId != null" style="width: 100%">
            <el-option v-for="r in BASE_ROLES" :key="r.code" :label="`${r.label}（${r.code}）`" :value="r.code" />
          </el-select>
          <p class="form-hint dim">自定义角色只能比 base 更窄；创建后不可更改 base（需重建）。</p>
        </el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option v-for="s in DATA_SCOPES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <p class="form-hint dim">v1 实际收敛仍按 base 角色的数据范围，此处仅登记。</p>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="form-hint dim">停用不影响已授予成员，重新启用即恢复。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitEdit">
          {{ editingId == null ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 权限设置 ============ -->
    <el-dialog
      v-model="permVisible"
      :title="`权限设置 · ${permTarget?.name || ''}`"
      width="720px"
      :close-on-click-modal="false"
    >
      <div v-loading="permLoading">
        <div class="perm-head">
          <span>
            基于「{{ baseLabel(permTarget?.baseRoleCode) }}」共 <b>{{ basePerms.length }}</b> 项权限；已收窄
            <b>{{ deniedCount }}</b> 项。
          </span>
          <span class="perm-actions">
            <el-button size="small" @click="checkAll">全选</el-button>
            <el-button size="small" @click="uncheckAll">全不选</el-button>
          </span>
        </div>
        <p class="form-hint dim">取消勾选 = 对该角色收窄（DENY）；勾选 = 保留 base 授予。自定义角色不可获得 base 之外的权限。</p>
        <el-scrollbar max-height="420">
          <div v-for="g in permGroups" :key="g.module" class="perm-group">
            <div class="perm-group-head">{{ g.label }}<span class="dim">（{{ g.codes.length }}）</span></div>
            <div class="perm-list">
              <el-checkbox
                v-for="c in g.codes"
                :key="c"
                :model-value="isChecked(c)"
                :disabled="!canWrite"
                @change="(v: string | number | boolean) => toggleCode(c, Boolean(v))"
              >
                <span class="perm-code mono">{{ c }}</span>
                <span class="dim perm-label">{{ permLabel(c) }}</span>
              </el-checkbox>
            </div>
          </div>
          <el-empty v-if="!permGroups.length && !permLoading" description="该 base 角色无权限点" :image-size="70" />
        </el-scrollbar>
      </div>
      <template #footer>
        <el-button @click="permVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="!canWrite" @click="submitPerm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.custom-role-view {
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
.body-card {
  padding: 16px;
}
.form-hint {
  font-size: 12px;
  margin: 4px 0 0;
  line-height: 1.5;
}
.perm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 13px;
}
.perm-actions {
  display: flex;
  gap: 6px;
}
.perm-group {
  margin-bottom: 12px;
}
.perm-group-head {
  font-size: 13px;
  font-weight: 700;
  padding: 6px 0 4px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  margin-bottom: 6px;
}
.perm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
}
.perm-list :deep(.el-checkbox) {
  height: auto;
  margin-right: 0;
}
.perm-code {
  font-size: 12px;
}
.perm-label {
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
