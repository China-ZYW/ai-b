<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import type { AuditNlResult, AuditQuerySlots } from '@/types'
import { actionLabel, resultLabel } from '@/utils/auditLabels'

/**
 * 审计智能查询面板（T11-3）：一句话 → 后端抽槽位（LLM 只产出过滤条件，绝不产出 SQL）→
 * 槽位 chips 回显 → 一键应用到父视图的人工筛选表单重载。
 *
 * <p>平台/组织两审计视图共用：查询函数由父视图注入（adminAuditApi.nlQuery / orgAuditApi.nlQuery），
 * 域差异只在这一行。面板显隐由父视图按 AUDIT/nl-query 门控（默认关，涉 LLM 红线）。
 *
 * <p>降级不报错：LLM 解析失败后端返回 degraded=true（HTTP 200），这里只提示回落人工筛选。
 */
const props = defineProps<{
  /** 查询实现：父视图绑定各自域的 nlQuery API（组织域仅本组织，平台域跨租户）。 */
  nlQuery: (question: string) => Promise<AuditNlResult>
}>()

const emit = defineEmits<{
  /** 点击「应用筛选」：把槽位交给父视图映射进筛选表单并重载列表。 */
  (e: 'apply', slots: AuditQuerySlots): void
}>()

const question = ref('')
const parsing = ref(false)
const result = ref<AuditNlResult | null>(null)

const slots = computed<AuditQuerySlots | null>(() =>
  result.value && !result.value.degraded ? result.value.slots ?? null : null
)

const SOURCE_LABELS: Record<string, string> = {
  AUDIT: '权限事件',
  OPS: '操作日志',
  QUERY: '问数记录'
}

async function run(): Promise<void> {
  const q = question.value.trim()
  if (!q) {
    ElMessage.info('请先输入查询内容')
    return
  }
  parsing.value = true
  try {
    result.value = await props.nlQuery(q)
    if (result.value.degraded) {
      ElMessage.warning('未能解析这个问题，请使用下方人工筛选条件')
    }
  } catch {
    // 403（门控关闭/无权限）与网络异常都按「AI 不可用」提示，不打断人工筛选
    result.value = null
  } finally {
    parsing.value = false
  }
}

function reset(): void {
  question.value = ''
  result.value = null
}

/** 槽位 chips：只展示非空项，动作/结果用与表格同一套中文标签（单一来源，防字典漂移）。 */
interface Chip {
  label: string
  value: string
}
const chips = computed<Chip[]>(() => {
  const s = slots.value
  if (!s) return []
  const out: Chip[] = []
  if (s.timeFrom || s.timeTo) {
    out.push({ label: '时间', value: `${s.timeFrom ?? '…'} ~ ${s.timeTo ?? '…'}` })
  }
  if (s.username) out.push({ label: '操作人', value: s.username })
  if (s.action) out.push({ label: '动作', value: actionLabel(s.action) })
  if (s.result) out.push({ label: '结果', value: resultLabel(s.result) })
  if (s.module) out.push({ label: '模块', value: s.module })
  if (s.keyword) out.push({ label: '关键词', value: s.keyword })
  if (s.sources?.length) {
    out.push({ label: '来源', value: s.sources.map((x) => SOURCE_LABELS[x] ?? x).join('、') })
  }
  return out
})
</script>

<template>
  <div class="nl-bar">
    <el-input
      v-model="question"
      placeholder="试试一句话查询，如：昨天 alice 登录失败 / 最近三天谁查了销售数据"
      clearable
      style="max-width: 520px"
      @keyup.enter="run"
    >
      <template #prefix>
        <el-icon><MagicStick /></el-icon>
      </template>
    </el-input>
    <el-button type="primary" plain :loading="parsing" @click="run">AI 查</el-button>
    <el-button v-if="result" text @click="reset">清除</el-button>
    <span v-if="result && !result.degraded" class="nl-hits">三源融合命中 {{ result.total }} 条</span>
  </div>

  <div v-if="slots" class="nl-slots">
    <el-tag v-for="c in chips" :key="c.label" size="small" type="info" class="nl-chip">
      {{ c.label }}：{{ c.value }}
    </el-tag>
    <el-button size="small" type="primary" plain @click="slots && emit('apply', slots)">
      应用到筛选
    </el-button>
  </div>
  <div v-if="slots?.note" class="nl-note">{{ slots.note }}</div>
</template>

<style scoped>
.nl-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.nl-hits {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.nl-slots {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.nl-chip {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nl-note {
  color: var(--el-color-warning);
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
