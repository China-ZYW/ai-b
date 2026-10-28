<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { metricApi } from '@/api/metric'
import { fewshotApi } from '@/api/fewshot'
import type { GovernanceIssue } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 待治理问题「转写」抽屉（T11-1 二期写侧闭环）：把一条聚合出的治理建议，经人工校对后沉淀为
 * 指标 / 少样本。
 *
 * <p><b>为何不自动转写</b>：待治理清单多为 FAILED / 低质 / 被踩的问法，其历史 SQL 并不可信，
 * 自动入库等于固化坏口径、违背「只建议不自动改」红线。故本抽屉自持<b>可编辑</b>的 SQL 草稿，
 * 按信号可信度分级警示，人工确认后才走<b>既有</b> {@code POST /api/metric}、{@code POST /api/fewshot}
 * （零新写接口、零新权限——tab 受众已持有两个 write 权限码）。
 *
 * <p>指标模式保存成功后自动 {@code refresh} 试跑一次，即时暴露仍不正确的 SQL。
 */
const props = defineProps<{
  modelValue: boolean
  /** 转写目标：metric=转指标，fewshot=转少样本。 */
  mode: 'metric' | 'fewshot'
  /** 来源待治理问题（提供预填问法 / SQL 草稿 / 可信度 / 数据源锚点）。 */
  issue: GovernanceIssue | null
  /** 当前数据源 id（优先于锚点，面板顶栏选择器决定）。 */
  datasourceId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'converted', payload: { mode: 'metric' | 'fewshot'; id?: number | null; refType?: string; refId?: number | null }): void
}>()

const { busy: submitting, run: runSubmit } = useActionGuard()

const form = reactive<{ name: string; description: string; question: string; sql: string }>({
  name: '',
  description: '',
  question: '',
  sql: ''
})

const isMetric = computed(() => props.mode === 'metric')
const title = computed(() => (isMetric.value ? '沉淀为指标' : '沉淀为少样本'))

/** 数据源：优先顶栏当前选择，回落 issue 锚点。 */
const effectiveDsId = computed<number | null>(() => {
  if (props.datasourceId != null) return props.datasourceId
  const raw = props.issue?.jumpAnchor?.datasourceId
  return typeof raw === 'number' ? raw : null
})

/** 代表问法（issue 簇内最长问句）。 */
const sourceQuestion = computed(() => props.issue?.question ?? '')
/**
 * 信任三态（T11-2 扩展自原 hasSuccessSql 二分）：
 * success=簇内命中可信成功 SQL，可直接转写；user=预填的是用户点踩时提交的期望口径（高价值线索但非权威，请复核）；
 * draft=失败/低质历史草稿，务必逐字校对。
 */
const trust = computed<'success' | 'user' | 'draft'>(() => {
  if (props.issue?.hasSuccessSql === true) return 'success'
  return props.issue?.userSuggestedSql?.trim() ? 'user' : 'draft'
})
/** 用户点踩时的口径说明（T11-2，可空）：展示在抽屉顶部供转写参考。 */
const userNote = computed(() => props.issue?.userNote?.trim() ?? '')

// 每次打开：用 issue 预填（SQL 草稿可编辑，名称默认取问法前 40 字）；
// T11-2：优先预填用户建议 SQL（替代错误的失败草稿），无建议退回 sampleSql——两者都是草稿，保存前必经人工复核
watch(
  () => props.modelValue,
  (open) => {
    if (!open || !props.issue) return
    form.question = sourceQuestion.value
    form.sql = props.issue.userSuggestedSql?.trim() ? props.issue.userSuggestedSql : props.issue.sampleSql ?? ''
    form.name = sourceQuestion.value.slice(0, 40)
    form.description = ''
  }
)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  const question = form.question.trim()
  const sql = form.sql.trim()
  if (!sql) {
    ElMessage.warning('SQL 不能为空，请补全或修正后再保存')
    return
  }
  const dsId = effectiveDsId.value
  if (dsId == null) {
    ElMessage.warning('缺少数据源，请在顶栏选择数据源后重试')
    return
  }

  if (isMetric.value) {
    if (!form.name.trim()) {
      ElMessage.warning('指标名称不能为空')
      return
    }
    await runSubmit(async () => {
      try {
        const vo = await metricApi.create({
          name: form.name.trim(),
          description: form.description.trim() || null,
          datasourceId: dsId,
          question: question || null,
          sql
        })
        emit('converted', { mode: 'metric', id: vo?.id ?? null, refType: 'METRIC', refId: vo?.id ?? null })
        // 保存后试跑一次：即时暴露仍不正确的 SQL（指标已落库，试跑失败仅提示，不回滚）
        if (vo?.id != null) {
          try {
            await metricApi.refresh(vo.id)
            ElMessage.success('已沉淀为指标，并试跑通过')
          } catch {
            ElMessage.warning('指标已保存，但试跑未通过——请到指标库复核 SQL 口径')
          }
        } else {
          ElMessage.success('已沉淀为指标')
        }
        close()
      } catch {
        // 拦截器已提示（如"该问数已保存为指标"去重、口径校验拒绝等）
      }
    })
    return
  }

  // 少样本模式：question + sqlText 均必填（与后端 FewShotController 校验一致）
  if (!question) {
    ElMessage.warning('问法不能为空')
    return
  }
  await runSubmit(async () => {
    try {
      const vo = await fewshotApi.create({
        datasourceId: dsId,
        question,
        sqlText: sql,
        intent: null,
        tags: null,
        enabled: 1
      })
      ElMessage.success('已沉淀为少样本')
      emit('converted', { mode: 'fewshot', id: vo?.id ?? null, refType: 'FEWSHOT', refId: vo?.id ?? null })
      close()
    } catch {
      // 拦截器已提示
    }
  })
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="560px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- 信任三态分级提示（T11-2）：成功样本=绿；用户建议=黄（高价值草稿非权威）；失败草稿=黄（逐字校对）——守住"不自动存坏 SQL"红线 -->
    <el-alert
      :type="trust === 'success' ? 'success' : 'warning'"
      :closable="false"
      show-icon
      class="trust-alert"
      :title="trust === 'success'
        ? '命中可信成功 SQL，可据此直接转写'
        : trust === 'user'
          ? '已预填用户点踩时提交的期望口径 · 请复核后转写'
          : '草稿来自失败 / 低质历史，务必人工校对后再保存'"
      :description="trust === 'success'
        ? '该簇内存在执行成功的 SQL，已预填至下方，确认口径无误即可保存。'
        : trust === 'user'
          ? '下方 SQL 是用户点踩时自愿填报的线索，比失败草稿更接近真实意图，但仍非权威口径——请核对表 / 字段 / 过滤条件确认正确再保存。'
          : '下方 SQL 为失败 / 低质问法的样例草稿，可能与真实意图不符——请逐字核对表 / 字段 / 过滤条件，确认正确再保存。'"
    />

    <!-- T11-2：用户口径说明（如有）：点踩时附的错因/期望描述，供管理员对照复核 -->
    <p v-if="userNote" class="user-note">
      <el-icon><ChatDotSquare /></el-icon>
      <span>点踩用户说明：{{ userNote }}</span>
    </p>

    <el-form :model="form" label-width="88px" class="convert-form">
      <el-form-item v-if="isMetric" label="指标名称" required>
        <el-input v-model="form.name" placeholder="例如：各地区已支付订单金额" maxlength="64" show-word-limit clearable />
      </el-form-item>
      <el-form-item v-if="isMetric" label="描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="可选：说明该指标的业务含义与口径"
        />
      </el-form-item>
      <el-form-item label="问法" :required="!isMetric">
        <el-input
          v-model="form.question"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="用户代表性问法"
        />
      </el-form-item>
      <el-form-item label="SQL" required>
        <el-input
          v-model="form.sql"
          type="textarea"
          :rows="8"
          class="mono"
          placeholder="SELECT ... （草稿可编辑，请核对后保存）"
        />
      </el-form-item>
    </el-form>

    <p class="tip muted">
      <el-icon><InfoFilled /></el-icon>
      保存将走既有创建接口{{ isMetric ? '（指标以已存 SQL 实时重跑取数，不消耗大模型额度）' : '（少样本以最高权重命中问数）' }}；
      数据源：<span class="mono">{{ effectiveDsId ?? '未选' }}</span>
    </p>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.trust-alert {
  margin-bottom: 14px;
}
.user-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: -6px 0 14px;
  padding: 8px 10px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
}
.user-note .el-icon {
  margin-top: 2px;
  color: var(--el-color-warning);
}
.convert-form {
  margin-bottom: 4px;
}
.tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 2px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.tip .el-icon {
  color: var(--ikb-lighter);
}
.mono {
  font-family: var(--font-mono, ui-monospace, Menlo, Consolas, monospace);
}
</style>
