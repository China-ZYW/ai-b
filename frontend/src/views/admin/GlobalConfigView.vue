<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminConfigApi } from '@/api/config'
import type { ConfigCategory, SysConfig } from '@/types'
import { fingerprint, useActionGuard, useRowBusy } from '@/utils/actionGuard'

/**
 * 平台默认配置（超管后台，/api/admin/config）：管理 GLOBAL scope 的平台默认值，作为所有租户的兜底。
 * 租户个性化覆盖在业务端「问数调优中心 → 结果与策略配置」维护（TENANT scope）。
 *
 * value_json 必须是合法 JSON：数字裸写、布尔 true/false、字符串加引号、数组/对象用括号。
 */
const CATEGORIES: { value: ConfigCategory; label: string; desc: string }[] = [
  { value: 'RESULT', label: '结果展示', desc: '默认视图、最大展示行数、数值格式' },
  { value: 'NL2SQL', label: '问数策略', desc: '少样本数、修复次数、对话轮数、空结果自修复、Prompt 规则、辅助模型交叉校验（含选表/接地/双生成/修复四类交叉复核）' },
  { value: 'CHART', label: '图表', desc: '饼图切片数、度量数上限、默认数值格式/小数位/图例位置、单位自动推断' },
  { value: 'STYLE', label: '样式', desc: '样式相关令牌（如有）' },
  { value: 'SYSTEM', label: '系统', desc: '其它系统级开关' },
  { value: 'FRESHNESS', label: '新鲜度', desc: '指标刷新 SLA 小时数（超时即标黄，指标可单独覆盖）' },
  { value: 'AI', label: 'AI 分析', desc: 'AI 图表决策 / 结果解读 / 质量校验 / 布局顾问开关 / 分析模型覆盖 / 交叉校验模型' },
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
  },
  {
    value: 'THEME',
    label: '主题',
    desc: '新建驾驶舱/大屏时默认采用的主题 code（theme_config.code，如 dark/light/tech/emerald）；只作用于新建，已有大屏不受影响'
  }
]

const category = ref<ConfigCategory>('NL2SQL')
const loading = ref(false)
const rows = ref<SysConfig[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await adminConfigApi.rows(category.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

function desc(): string {
  return CATEGORIES.find((c) => c.value === category.value)?.desc || ''
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const isEdit = ref(false)
/** 上一次成功提交的内容指纹：拦住"同一份内容连点保存"（版本号是审计事实，不该被空提交顶上去）。 */
const lastSubmitted = ref<string | null>(null)
// 保存：连点重入由护栏早退；管控开关按行分桶（不同行可并发，同一行在途时开关禁用）
const { busy: submitting, run: runSubmit } = useActionGuard()
const rowBusy = useRowBusy<number>()

interface FormState {
  configKey: string
  valueJson: string
  description: string
  /** 平台管控：开启后该键在所有租户锁死为平台值（租户覆盖失效且不可写）。 */
  managed: boolean
}
const emptyForm = (): FormState => ({ configKey: '', valueJson: '', description: '', managed: false })
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
    valueJson: pretty(r.valueJson ?? ''),
    description: r.description ?? '',
    managed: r.managed === 1
  })
  lastSubmitted.value = null
  drawerVisible.value = true
}

/**
 * 抽屉内容指纹。注意不要把「打开时的初始内容」也记成"已提交"——编辑框里的 JSON 会被 pretty 重排，
 * 与库里存的原始字符串可能不同，那种差异该由后端按 JSON 语义判定（`ConfigService.sameJson`），
 * 前端只负责拦「同一份内容连点」。所以初始值取 null：第一次点击总是真的发出去。
 *
 * <p>序列化用公共 `fingerprint`（键序无关），不再各页各写一份 `JSON.stringify`。
 */
function formFingerprint(): string {
  return fingerprint([form.configKey.trim(), form.valueJson.trim(), form.description.trim(), form.managed])
}

function pretty(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function validJson(text: string): boolean {
  if (!text.trim()) return true
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
    ElMessage.warning('值必须是合法 JSON')
    return
  }
  const fp = formFingerprint()
  if (fp === lastSubmitted.value) {
    ElMessage.info('内容未发生变化，无需重复保存')
    return
  }
  // 连点护栏（重入早退）由 actionGuard 统一提供，不再手写 `if (submitting) return`
  await runSubmit(async () => {
    try {
      const saved = await adminConfigApi.upsert({
        category: category.value,
        configKey: form.configKey.trim(),
        valueJson: form.valueJson.trim() || null,
        description: form.description.trim() || null,
        managed: form.managed
      })
      lastSubmitted.value = fp
      drawerVisible.value = false
      if (saved == null) {
        // 后端幂等护栏命中：与本行当前内容一致 → 未写库、版本号未变
        ElMessage.info('内容与当前配置一致，未写入（版本号保持不变）')
      } else {
        ElMessage.success(form.managed ? '已保存，该配置项对全部租户锁死' : '平台默认配置已保存')
      }
      await load()
    } catch {
      // 拦截器已提示；失败不记录指纹，允许原样重试
      lastSubmitted.value = null
    }
  })
}

/**
 * 表格内直接切换管控（不改值，只改锁）。
 *
 * <p>只发 category/configKey/valueJson/description/managed 五个字段，后端 upsert 为幂等更新，
 * version 会 +1。失败时回读列表，避免开关停在与服务端不一致的位置。
 */
async function toggleManaged(r: SysConfig, v: unknown): Promise<void> {
  const next = v === true
  if (next) {
    try {
      await ElMessageBox.confirm(
        `确定将「${r.configKey}」设为平台管控吗？\n所有租户对该项的覆盖将立即失效（数据保留，解除管控后自动恢复），且租户将无法再修改。`,
        '平台管控确认',
        { type: 'warning', confirmButtonText: '设为管控', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  // 行级护栏：同一行在途时开关已禁用（不会发出第二次翻转），此处再早退一次兜底
  await rowBusy.run(r.id ?? -1, async () => {
    try {
      const saved = await adminConfigApi.upsert({
        category: category.value,
        configKey: r.configKey,
        valueJson: r.valueJson ?? null,
        description: r.description ?? null,
        managed: next
      })
      if (saved == null) {
        ElMessage.info('管控状态未发生变化')
      } else {
        ElMessage.success(
          next ? `「${r.configKey}」已设为平台管控：全部租户不可覆盖` : `「${r.configKey}」已解除管控：租户可自行覆盖`
        )
      }
    } catch {
      // 拦截器已提示
    } finally {
      await load() // 无论成败都回读真实状态，避免开关停在错误位置
    }
  })
}

async function onRemove(r: SysConfig): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm(
      `确定删除平台默认「${r.configKey}」吗？删除后回落代码内置默认。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminConfigApi.remove(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="global-config-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">平台默认配置</h2>
        <p class="ph-desc">
          管理 GLOBAL 平台默认值（所有租户兜底）。租户可在此基础上做个性化覆盖；此处修改对所有未覆盖的租户即时生效。
          <strong>「平台管控」开关</strong>可把某项从「平台默认」升格为「平台强制」：锁死后全部租户不可覆盖，
          用于统一控制各租户的同名功能开关。
        </p>
      </div>
      <el-button type="primary" class="add-btn" @click="openCreate">
        <el-icon><Plus /></el-icon> 新增配置
      </el-button>
    </div>

    <div class="toolbar card">
      <el-radio-group v-model="category" @change="load">
        <el-radio-button v-for="c in CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</el-radio-button>
      </el-radio-group>
      <span class="tb-desc">{{ desc() }}</span>
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="rows" style="width: 100%" row-key="id" border stripe>
        <el-table-column label="配置键" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.configKey }}</span></template>
        </el-table-column>
        <el-table-column label="值（JSON）" min-width="280" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono small">{{ row.valueJson || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="平台管控" width="120" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.managed === 1"
              :loading="rowBusy.isBusy(row.id)"
              :disabled="rowBusy.isBusy(row.id)"
              inline-prompt
              active-text="锁"
              inactive-text="放"
              @change="(v: unknown) => toggleManaged(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">v{{ row.version ?? 1 }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
            <el-tooltip content="删除配置项" placement="top" class="ops-danger">
              <el-button size="small" text type="danger" @click="onRemove(row)"><el-icon><Delete /></el-icon></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该平台分类暂无配置行" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑平台默认配置' : '新增平台默认配置'" size="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="分类">
          <el-tag type="warning" effect="plain" round>GLOBAL · {{ category }}</el-tag>
        </el-form-item>
        <el-form-item label="配置键" required>
          <el-input v-model="form.configKey" :disabled="isEdit" placeholder="例如：few-shot-limit" maxlength="128" clearable />
        </el-form-item>
        <el-form-item label="值(JSON)" required>
          <el-input
            v-model="form.valueJson"
            type="textarea"
            :rows="6"
            class="mono"
            spellcheck="false"
            placeholder='合法 JSON：数字 3 / 布尔 true / 字符串 "chart" / 数组 ["a","b"]'
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="平台管控">
          <el-switch v-model="form.managed" active-text="锁死为平台值" inactive-text="租户可覆盖" />
          <span class="field-hint">
            开启后该配置项在<strong>所有租户</strong>固定为本值：租户已有的覆盖行立即失效、且无法再修改。
            管控期间不删除任何租户数据，解除管控后覆盖自动恢复。
          </span>
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
.global-config-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
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
  max-width: 720px;
}
.add-btn {
  background: var(--grad-btn);
  border: none;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.tb-desc {
  font-size: 12.5px;
  color: var(--text-secondary);
}
.table-card {
  padding: 8px 12px 12px;
}
.small {
  font-size: 12px;
}
.field-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}
</style>
