<script setup lang="ts">
/**
 * 定时报表创建/编辑弹层（T12-1）。自持表单状态：弹层打开时按 `edit`（编辑）
 * 或 `presetTemplateId`（从模板行「定时」进入）初始化，提交走 reportApi 后通知父级刷新。
 *
 * 校验口径与后端 `ReportService.validate` 对齐（freq/channel 闭集、DAILY/WEEKLY 必配时刻、
 * WEEKLY 必配周几、EMAIL/BOTH 必配收件人）——前端先拦是给体验，后端拦才是安全边界。
 * 参数固定值：带 :param 的模板逐参数给输入框，留空即 NULL（定时场景无人填参）。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reportApi } from '@/api/report'
import { listTemplates, type SavedQueryVO } from '@/api/query'
import type { ReportFreq, ReportChannel, ReportUpsertCmd, ScheduledReportVO } from '@/types'

const router = useRouter()

const props = defineProps<{
  modelValue: boolean
  /** 创建模式：从模板行进入时预选该模板。 */
  presetTemplateId?: number | null
  /** 编辑模式：非空则整表单按此回填（覆盖式更新）。 */
  edit?: ScheduledReportVO | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const WEEKDAYS: { value: string; label: string }[] = [
  { value: '1', label: '周一' },
  { value: '2', label: '周二' },
  { value: '3', label: '周三' },
  { value: '4', label: '周四' },
  { value: '5', label: '周五' },
  { value: '6', label: '周六' },
  { value: '7', label: '周日' }
]

/** 时间变量提示清单（T12-1b）：与后端 ReportParamExpander 的 token 集一致；
 *  写成常量而非模板插值，避开 Vue mustache 在字符串内 `}}` 处截断的解析坑。 */
const TOKEN_HINTS: string[] = [
  '{{today}}',
  '{{yesterday}}',
  '{{tomorrow}}',
  '{{monday}}',
  '{{month_start}}',
  '{{month_end}}',
  '{{prev_month_start}}',
  '{{prev_month_end}}',
  '{{now}}',
  '{{days_ago:7}}',
  '{{hours_ago:5}}',
  '{{昨天}}',
  '{{yesterday:yyyyMMdd}}'
]

const templates = ref<SavedQueryVO[]>([])
const saving = ref(false)

const form = reactive({
  name: '',
  templateId: null as number | null,
  freq: 'DAILY' as ReportFreq,
  runTime: '09:00',
  weekdays: [] as string[],
  channel: 'INAPP' as ReportChannel,
  inappUids: '',
  emailTargets: '',
  enabled: true,
  /** 参数名 → 固定值输入（随所选模板 paramNames 重建）。 */
  paramValues: {} as Record<string, string>
})

/** 当前所选模板（决定参数输入区与提示）。 */
const pickedTpl = computed(() => templates.value.find((t) => t.id === form.templateId) ?? null)
const paramNames = computed<string[]>(() => pickedTpl.value?.paramNames ?? [])

/** 打开弹层即初始化：编辑回填 / 创建预选；模板清单每次拉（新存模板立即可见）。 */
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    templates.value = (await listTemplates(null).catch(() => [])) ?? []
    const e = props.edit
    if (e) {
      form.name = e.name
      form.templateId = e.templateId
      form.freq = e.freq
      form.runTime = e.runTime || '09:00'
      form.weekdays = (e.weekdays ?? '').split(',').map((s) => s.trim()).filter(Boolean)
      form.channel = e.channel
      form.inappUids = e.inappUids ?? ''
      form.emailTargets = e.emailTargets ?? ''
      form.enabled = e.enabled !== false
    } else {
      form.name = ''
      form.templateId = props.presetTemplateId ?? null
      form.freq = 'DAILY'
      form.runTime = '09:00'
      form.weekdays = []
      form.channel = 'INAPP'
      form.inappUids = ''
      form.emailTargets = ''
      form.enabled = true
    }
    rebuildParamValues()
  }
)

/** 换模板 → 参数输入区按新 paramNames 重建（旧值不跨模板残留）。 */
watch(() => form.templateId, rebuildParamValues)

function rebuildParamValues() {
  const next: Record<string, string> = {}
  // 编辑模式回填已存 params_json 的固定值（能解则续用，解不动即空）
  let seed: Record<string, unknown> = {}
  if (props.edit?.paramsJson) {
    try {
      seed = JSON.parse(props.edit.paramsJson) as Record<string, unknown>
    } catch {
      seed = {}
    }
  }
  for (const n of pickedTpl.value?.paramNames ?? []) {
    next[n] = seed[n] != null ? String(seed[n]) : ''
  }
  form.paramValues = next
}

const canSubmit = computed(() => {
  if (!form.name.trim() || form.templateId == null) return false
  if (form.freq !== 'HOURLY' && !/^\d{2}:\d{2}$/.test(form.runTime)) return false
  if (form.freq === 'WEEKLY' && !form.weekdays.length) return false
  if (form.channel !== 'INAPP' && !form.emailTargets.trim()) return false
  return true
})

function buildCmd(): ReportUpsertCmd {
  const vals = Object.values(form.paramValues)
  const params = vals.some((v) => v.trim() !== '')
    ? JSON.stringify(
        Object.fromEntries(Object.entries(form.paramValues).filter(([, v]) => v.trim() !== ''))
      )
    : null
  return {
    name: form.name.trim(),
    templateId: form.templateId!,
    paramsJson: params,
    freq: form.freq,
    // HOURLY 忽略时刻/周几列（后端也会清，前端先传 null 保持所见即所存）
    runTime: form.freq === 'HOURLY' ? null : form.runTime,
    weekdays: form.freq === 'WEEKLY' ? form.weekdays.slice().sort().join(',') : null,
    channel: form.channel,
    inappUids: form.inappUids.trim() || null,
    emailTargets: form.emailTargets.trim() || null,
    enabled: form.enabled
  }
}

async function onSubmit() {
  if (!canSubmit.value || form.templateId == null) return
  saving.value = true
  try {
    const cmd = buildCmd()
    if (props.edit) {
      await reportApi.update(props.edit.id, cmd)
      ElMessage.success('定时报表已更新')
    } else {
      await reportApi.create(cmd)
      ElMessage.success('定时报表已创建，将按所选频率自动推送')
    }
    visible.value = false
    emit('saved')
  } catch (e: unknown) {
    // 请求层拦截器已弹全局错误提示，这里只兜文案（不再二次 toast）
    console.warn('保存定时报表失败', e)
  } finally {
    saving.value = false
  }
}

/** 去高级查询新建模板：先收弹层再路由，避免隐藏弹层跨页残留；新存模板回本页重开即可见。 */
function goCreateTemplate() {
  visible.value = false
  router.push('/app/sql')
}
</script>

<template>
  <el-dialog v-model="visible" :title="edit ? '编辑定时报表' : '设为定时报表'" width="560px" align-center>
    <el-form label-width="92px" @submit.prevent>
      <el-form-item label="报表名" required>
        <el-input v-model="form.name" maxlength="64" placeholder="同账号下唯一，如「日销售额播报」" />
      </el-form-item>
      <el-form-item label="查询模板" required>
        <el-select v-model="form.templateId" placeholder="选择已保存的查询模板" class="rf-full">
          <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id">
            <span>{{ t.name }}</span>
            <span class="rf-opt-desc">{{ t.description || '' }}</span>
          </el-option>
          <template #empty>
            <p class="rf-tpl-empty">还没有可用模板</p>
          </template>
        </el-select>
        <!-- 新建模板唯一入口在高级查询；报表侧不内建模板，给一条直达链接免用户走投无路 -->
        <el-button size="small" text type="primary" class="rf-tpl-create" @click="goCreateTemplate">
          没有合适模板？去高级查询新建 →
        </el-button>
      </el-form-item>

      <template v-if="paramNames.length">
        <el-form-item v-for="n in paramNames" :key="n" :label="`参数 ${n}`">
          <el-input
            v-model="form.paramValues[n]"
            size="small"
            placeholder="固定值或时间变量（如 {{yesterday}}），留空=NULL"
          />
        </el-form-item>
        <div class="rf-param-hint">
          时间变量每次执行按当时日期展开（日报自动跑昨天数据）：
          <code v-for="t in TOKEN_HINTS" :key="t">{{ t }}</code>
          <span>；支持中文别名；可加 :格式 后缀适配分区表（如 yyyyMMdd）；能嵌在文本中混排</span>
        </div>
      </template>

      <el-form-item label="频率" required>
        <el-radio-group v-model="form.freq">
          <el-radio-button value="DAILY">每天</el-radio-button>
          <el-radio-button value="WEEKLY">每周</el-radio-button>
          <el-radio-button value="HOURLY">每小时</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.freq !== 'HOURLY'" label="执行时刻" required>
        <el-time-picker v-model="form.runTime" format="HH:mm" value-format="HH:mm" placeholder="如 09:00" />
        <span class="rf-hint">错过的周期不补跑（宁漏不重），从下一周期生效</span>
      </el-form-item>
      <el-form-item v-if="form.freq === 'WEEKLY'" label="周几" required>
        <el-checkbox-group v-model="form.weekdays">
          <el-checkbox v-for="w in WEEKDAYS" :key="w.value" :value="w.value">{{ w.label }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>

      <el-form-item label="推送渠道" required>
        <el-radio-group v-model="form.channel">
          <el-radio-button value="INAPP">站内信</el-radio-button>
          <el-radio-button value="EMAIL">邮件</el-radio-button>
          <el-radio-button value="BOTH">两者</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.channel !== 'EMAIL'" label="站内接收人">
        <el-input v-model="form.inappUids" placeholder="用户 id 逗号分隔，如 2,3（留空=只发我自己）" />
      </el-form-item>
      <el-form-item v-if="form.channel !== 'INAPP'" label="邮件收件人" required>
        <el-input v-model="form.emailTargets" type="textarea" :rows="2" placeholder="a@x.com, b@y.com" />
      </el-form-item>
      <el-alert
        v-if="form.channel !== 'INAPP'"
        type="warning"
        :closable="false"
        show-icon
        class="rf-warn"
        title="邮件外发将携带查询结果（前 20 行），请确认数据可对外发送"
      />
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="onSubmit">
        {{ edit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.rf-full {
  width: 100%;
}
.rf-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.rf-param-hint {
  margin: -8px 0 12px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--text-secondary);
  background: var(--el-fill-color-lighter, #f5f7fa);
  border-radius: 4px;
}
.rf-param-hint code {
  display: inline-block;
  margin: 0 4px 0 0;
  padding: 0 4px;
  font-size: 11px;
  color: var(--el-color-primary, #2457ff);
  background: #fff;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 3px;
}
.rf-opt-desc {
  float: right;
  margin-left: 12px;
  font-size: 11.5px;
  color: var(--text-secondary);
}
.rf-tpl-create {
  margin-top: 4px;
  padding: 0;
  height: auto;
}
.rf-tpl-empty {
  padding: 12px 0;
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
}
.rf-warn {
  margin: 0 0 12px 92px;
  width: calc(100% - 92px);
}
</style>
