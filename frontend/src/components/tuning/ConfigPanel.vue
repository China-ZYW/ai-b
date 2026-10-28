<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configApi } from '@/api/config'
import type { ConfigCategory, EffectiveConfig, SysConfig } from '@/types'
import { fingerprint, useActionGuard } from '@/utils/actionGuard'

/**
 * 配置管理面板（配置化升级 V2，租户侧 /api/config）：按 category 维护本租户的配置覆盖行。
 * 生效值 = 平台默认（GLOBAL）← 本租户覆盖（TENANT）← 代码兜底，三级回退；此处只编辑 TENANT 覆盖。
 *
 * value_json 必须是合法 JSON：数字裸写（3）、布尔（true）、字符串（"chart"）、数组（[...]）、对象（{...}）。
 */
const CATEGORIES: { value: ConfigCategory; label: string; desc: string }[] = [
  { value: 'RESULT', label: '结果展示', desc: '默认视图、最大展示行数、数值格式' },
  { value: 'NL2SQL', label: '问数策略', desc: '少样本数、修复次数、对话轮数、空结果自修复、Prompt 规则、辅助模型交叉校验（含选表/接地/双生成/修复四类交叉复核）' },
  { value: 'CHART', label: '图表', desc: '饼图切片数、度量数上限、默认数值格式/小数位/图例位置、单位自动推断' },
  { value: 'STYLE', label: '样式', desc: '样式相关令牌（如有）' },
  { value: 'SYSTEM', label: '系统', desc: '其它系统级开关' },
  { value: 'FRESHNESS', label: '新鲜度', desc: '指标刷新 SLA 小时数（超时即标黄，指标可单独覆盖）' },
  { value: 'AI', label: 'AI 分析', desc: 'AI 图表决策 / 结果解读 / 质量校验 / 布局顾问开关与分析模型覆盖' },
  { value: 'ASK', label: '问数技能', desc: '各答问技能启停、追问改写、域外护栏、术语词典、权限护栏、路由/改写交叉复核' },
  {
    value: 'ATTRIBUTION',
    label: '归因与血缘',
    desc: '归因模块总开关（enabled）、问数结果内归因面板（result-panel）、血缘开关、单轮澄清、原始 SQL 可见角色白名单'
  },
  { value: 'AUDIT', label: '审计', desc: '全局操作日志请求级采集开关' },
  {
    value: 'METRIC',
    label: '指标自动分析',
    desc: '指标后台自动分析 Skill 总开关（扫描库表自动识别指标/维度并建指标）'
  },
  {
    value: 'COCKPIT',
    label: 'AI 自动化大屏',
    desc: '驾驶舱 AI 自动化大屏 Skill 总开关（基于指标自动编排大屏）'
  }
]

const category = ref<ConfigCategory>('RESULT')
const loading = ref(false)
const rows = ref<SysConfig[]>([])
const effective = ref<EffectiveConfig>({})
/**
 * 平台管控键清单，元素形如 `"CATEGORY:configKey"`（GET /api/config/managed）。
 * 被管控项由超管锁死为平台值：本租户的覆盖不生效、写入会被后端以 40301 拒绝，故此处只做展示与禁用。
 */
const managedKeys = ref<string[]>([])
/** 合并视图的说明字典（category → key → description）：「当前生效值」表的中文说明来自这里。 */
const descMap = ref<Record<string, Record<string, string>>>({})

const categoryDesc = computed(() => CATEGORIES.find((c) => c.value === category.value)?.desc || '')

/** 某配置键在当前分类下是否被平台管控（带分类前缀比较，避免跨分类同名键误判）。 */
function isManaged(key: string): boolean {
  return managedKeys.value.includes(`${category.value}:${key}`)
}

/** 当前分类下被平台管控的键数量（顶部提示用）。 */
const managedInCategory = computed(
  () => managedKeys.value.filter((k) => k.startsWith(`${category.value}:`)).length
)

const effectiveRows = computed(() => {
  const map = effective.value?.[category.value] ?? {}
  const desc = descMap.value?.[category.value] ?? {}
  return Object.keys(map)
    .sort()
    .map((k) => ({ key: k, value: map[k], desc: desc[k] ?? '', managed: isManaged(k) }))
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const [r, e, m, d] = await Promise.all([
      configApi.rows(category.value),
      configApi.effective(),
      configApi.managed(),
      configApi.descriptions()
    ])
    rows.value = r
    effective.value = e
    managedKeys.value = m ?? []
    descMap.value = d ?? {}
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

function onCategoryChange(): void {
  void load()
}

function pretty(v: unknown): string {
  if (v == null) return '—'
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const isEdit = ref(false)
/** 上一次成功提交的内容指纹：拦住"同一份内容连点保存"（后端 `version` 是审计事实，不该被空提交顶上去）。 */
const lastSubmitted = ref<string | null>(null)
// 保存：连点重入由护栏早退，不再手写 `if (submitting) return`
const { busy: submitting, run: runSubmit } = useActionGuard()

interface FormState {
  configKey: string
  valueJson: string
  description: string
}
const emptyForm = (): FormState => ({ configKey: '', valueJson: '', description: '' })
const form = reactive<FormState>(emptyForm())

function openCreate(): void {
  editingId.value = null
  isEdit.value = false
  Object.assign(form, emptyForm())
  lastSubmitted.value = null
  drawerVisible.value = true
}

function openEdit(r: SysConfig): void {
  editingId.value = r.id ?? null
  isEdit.value = true
  Object.assign(form, {
    configKey: r.configKey ?? '',
    valueJson: r.valueJson ?? '',
    description: r.description ?? ''
  })
  lastSubmitted.value = null
  drawerVisible.value = true
}

/**
 * 抽屉内容指纹。初始值取 null 而非"打开时的内容"——编辑框里的 JSON 可能被重排，
 * 与库中原始字符串的差异应交由后端按 JSON 语义判定（`ConfigService.sameJson`），
 * 前端只负责拦「同一份内容连点」。
 *
 * <p>序列化用公共 `fingerprint`（键序无关），不再各页各写一份 `JSON.stringify`。
 */
function formFingerprint(): string {
  return fingerprint([form.configKey.trim(), form.valueJson.trim(), form.description.trim()])
}

function validJson(text: string): boolean {
  if (!text.trim()) return true // 允许清空（删除覆盖）
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}

async function onSubmit(): Promise<void> {
  if (!form.configKey.trim()) {
    ElMessage.warning('请填写配置键')
    return
  }
  if (!validJson(form.valueJson)) {
    ElMessage.warning('值必须是合法 JSON（数字裸写、布尔 true/false、字符串加引号、数组/对象用括号）')
    return
  }
  // 前端先行拦截：给出可读原因，而不是让用户撞后端的 40301
  if (isManaged(form.configKey.trim())) {
    ElMessage.warning(`「${form.configKey.trim()}」已由平台统一管控，租户不可覆盖；如需调整请联系平台管理员`)
    return
  }
  const fp = formFingerprint()
  if (fp === lastSubmitted.value) {
    ElMessage.info('内容未发生变化，无需重复保存')
    return
  }
  // 连点护栏（重入早退）由 actionGuard 统一提供
  await runSubmit(async () => {
    try {
      const saved = await configApi.upsert({
        category: category.value,
        configKey: form.configKey.trim(),
        valueJson: form.valueJson.trim() || null,
        description: form.description.trim() || null
      })
      lastSubmitted.value = fp
      drawerVisible.value = false
      if (saved == null) {
        // 后端幂等护栏命中：与本租户当前覆盖一致 → 未写库、版本号未变
        ElMessage.info('内容与当前覆盖一致，未写入（版本号保持不变）')
      } else {
        ElMessage.success('配置已保存并即时生效')
      }
      await load()
    } catch {
      // 拦截器已提示；失败不记录指纹，允许原样重试
      lastSubmitted.value = null
    }
  })
}

async function onRemove(r: SysConfig): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm(
      `确定删除覆盖「${r.configKey}」吗？删除后将回落平台默认值。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await configApi.remove(r.id)
    ElMessage.success('已删除，恢复平台默认')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="config-panel">
    <div class="panel-toolbar">
      <div class="tb-left">
        <el-radio-group v-model="category" @change="onCategoryChange">
          <el-radio-button v-for="c in CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</el-radio-button>
        </el-radio-group>
        <span class="tb-desc">{{ categoryDesc }}</span>
      </div>
      <div class="tb-actions">
        <el-button size="default" :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button type="primary" size="default" @click="openCreate"><el-icon><Plus /></el-icon> 新增覆盖</el-button>
      </div>
    </div>

    <!-- 生效值（只读参考） -->
    <section class="eff-section card">
      <h3 class="sec-title">当前生效值（平台默认 + 本租户覆盖合并后）</h3>
      <el-alert
        v-if="managedInCategory"
        class="managed-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="`本分类有 ${managedInCategory} 项由平台统一管控`"
        description="带「平台管控」标记的项已锁死为平台值：本租户的覆盖不生效、也无法修改；解除管控后覆盖自动恢复。"
      />
      <el-table v-if="effectiveRows.length" :data="effectiveRows" size="small" border style="width: 100%">
        <el-table-column prop="key" label="配置键" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.key }}</span></template>
        </el-table-column>
        <el-table-column label="生效值" min-width="200">
          <template #default="{ row }"><span class="mono small">{{ pretty(row.value) }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.desc || '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.managed" type="warning" size="small" effect="plain">平台管控</el-tag>
            <span v-else class="muted small">可覆盖</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="该分类暂无生效配置" :image-size="70" />
    </section>

    <!-- 本租户覆盖行（可编辑） -->
    <section class="ovr-section">
      <h3 class="sec-title">本租户覆盖行</h3>
      <el-table v-loading="loading" :data="rows" style="width: 100%" row-key="id" border stripe size="small">
        <el-table-column label="配置键" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.configKey }}</span></template>
        </el-table-column>
        <el-table-column label="值（JSON）" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono small">{{ row.valueJson || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="isManaged(row.configKey)" type="warning" size="small" effect="plain">平台管控</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">生效中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              text
              :disabled="isManaged(row.configKey)"
              :title="isManaged(row.configKey) ? '该配置项已由平台统一管控，租户不可修改' : ''"
              @click="openEdit(row)"
            >
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button
              size="small"
              text
              type="danger"
              :title="isManaged(row.configKey) ? '该覆盖已失效（生效值取平台管控值），删除仅作清理' : ''"
              @click="onRemove(row)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="本租户暂无覆盖，全部使用平台默认" :image-size="70" />
        </template>
      </el-table>
    </section>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑配置覆盖' : '新增配置覆盖'" size="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="分类">
          <el-tag type="info" effect="plain" round>{{ category }}</el-tag>
        </el-form-item>
        <el-form-item label="配置键" required>
          <el-input v-model="form.configKey" :disabled="isEdit" placeholder="例如：max-display-rows" maxlength="128" clearable />
        </el-form-item>
        <el-form-item label="值(JSON)" required>
          <el-input
            v-model="form.valueJson"
            type="textarea"
            :rows="4"
            class="mono"
            placeholder='合法 JSON：数字 500 / 布尔 true / 字符串 "chart" / 数组 ["a","b"]'
          />
          <span class="field-hint muted">留空表示删除该覆盖，回落平台默认</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="可选" maxlength="255" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.config-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-desc {
  font-size: 12.5px;
  color: var(--text-secondary);
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.eff-section {
  padding: 14px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 700;
  margin: 0 0 10px;
}
.ovr-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
}
.managed-alert {
  margin-bottom: 10px;
}
</style>
