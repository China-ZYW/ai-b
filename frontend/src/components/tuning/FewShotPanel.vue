<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fewshotApi } from '@/api/fewshot'
import type { FewShotExample } from '@/types'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'

/**
 * 样例库管理面板（配置化升级 V2）：维护 curated「问法 → SQL」样例。
 * 样例以最高权重合并进少样本（few-shot），是提升问数准确率最直接的可运营抓手；
 * 用户在问数页点赞且执行成功的历史也会自动进入晋升池（source=HISTORY_PROMOTED，此处只读展示）。
 */
const props = defineProps<{ datasourceId: number | null }>()

const loading = ref(false)
const rows = ref<FewShotExample[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await fewshotApi.list(props.datasourceId ?? undefined)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, () => void load(), { immediate: true })

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const isEdit = ref(false)
// 保存 / 行内启停：连点只提交一次（启停按行分桶，两行并发互不干扰）
const { busy: submitting, run: runSubmit } = useActionGuard()
const enabledBusy = useRowBusy<number>()

interface FormState {
  question: string
  sqlText: string
  intent: string
  tags: string
  enabled: number
}
const emptyForm = (): FormState => ({ question: '', sqlText: '', intent: '', tags: '', enabled: 1 })
const form = reactive<FormState>(emptyForm())

function openCreate(): void {
  editingId.value = null
  isEdit.value = false
  Object.assign(form, emptyForm())
  drawerVisible.value = true
}

function openEdit(r: FewShotExample): void {
  editingId.value = r.id ?? null
  isEdit.value = true
  Object.assign(form, {
    question: r.question ?? '',
    sqlText: r.sqlText ?? '',
    intent: r.intent ?? '',
    tags: r.tags ?? '',
    enabled: r.enabled ?? 1
  })
  drawerVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.question.trim() || !form.sqlText.trim()) {
    ElMessage.warning('问题与 SQL 不能为空')
    return
  }
  await runSubmit(async () => {
    try {
      const payload: FewShotExample = {
        datasourceId: props.datasourceId,
        question: form.question.trim(),
        sqlText: form.sqlText.trim(),
        intent: form.intent.trim() || null,
        tags: form.tags.trim() || null,
        enabled: form.enabled
      }
      if (isEdit.value && editingId.value != null) {
        await fewshotApi.update({ ...payload, id: editingId.value })
        ElMessage.success('样例已更新')
      } else {
        await fewshotApi.create(payload)
        ElMessage.success('样例已创建')
      }
      drawerVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

/**
 * 行内启停。
 *
 * <p>用行级护栏而非无护栏：开关是 {@code :model-value} 单向绑定的，翻转后到回读完成之间，
 * 开关停在"新值"上——若此时用户再点一下，就发出与在途请求**相反**的第二次写入，
 * 请求层去重也救不了（body 不同），最终落库值取决于两个请求谁后到。故在途期间必须禁用该行开关。
 */
async function toggleEnabled(r: FewShotExample, enabled: number): Promise<void> {
  if (r.id == null) return
  await enabledBusy.run(r.id, async () => {
    try {
      await fewshotApi.update({ ...r, enabled })
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读，避免开关停留在与后端不一致的位置
      await load()
    }
  })
}

async function onRemove(r: FewShotExample): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm(`确定删除样例「${r.question}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await fewshotApi.remove(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

function sourceLabel(s?: string | null): string {
  return s === 'HISTORY_PROMOTED' ? '历史晋升' : '人工维护'
}
</script>

<template>
  <div class="fewshot-panel">
    <div class="panel-toolbar">
      <p class="tb-tip">
        维护标准「问法 → SQL」样例，问数时以最高权重命中；数据源为空表示通用样例。
      </p>
      <div class="tb-actions">
        <el-button size="default" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button type="primary" size="default" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建样例
        </el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" style="width: 100%" row-key="id" border stripe size="small">
      <el-table-column prop="question" label="问法" min-width="200" show-overflow-tooltip />
      <el-table-column label="SQL" min-width="240" show-overflow-tooltip>
        <template #default="{ row }"><span class="mono small">{{ row.sqlText }}</span></template>
      </el-table-column>
      <el-table-column prop="intent" label="意图" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.intent || '—' }}</template>
      </el-table-column>
      <el-table-column prop="tags" label="标签" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.tags || '—' }}</template>
      </el-table-column>
      <el-table-column label="来源" width="96" align="center">
        <template #default="{ row }">
          <el-tag :type="row.source === 'HISTORY_PROMOTED' ? 'success' : 'primary'" effect="plain" size="small" round>
            {{ sourceLabel(row.source) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="命中" width="72" align="center">
        <template #default="{ row }">{{ row.useCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="启用" width="76" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :active-value="1"
            :inactive-value="0"
            :loading="enabledBusy.isBusy(row.id)"
            :disabled="enabledBusy.isBusy(row.id)"
            @change="(v: number) => toggleEnabled(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
          <el-button size="small" text type="danger" @click="onRemove(row)"><el-icon><Delete /></el-icon></el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无样例，点击「新建样例」添加" :image-size="80" />
      </template>
    </el-table>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑样例' : '新建样例'" size="520px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="问法" required>
          <el-input
            v-model="form.question"
            type="textarea"
            :rows="2"
            placeholder="例如：每个地区的已支付订单总金额"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="SQL" required>
          <el-input
            v-model="form.sqlText"
            type="textarea"
            :rows="6"
            class="mono"
            placeholder="SELECT region, SUM(amount) FROM orders WHERE status = 'PAID' GROUP BY region"
          />
        </el-form-item>
        <el-form-item label="意图">
          <el-input v-model="form.intent" placeholder="可选，例如：聚合/明细/趋势" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="可选，多个用逗号分隔" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
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
.fewshot-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-tip {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  max-width: 620px;
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.small {
  font-size: 12px;
}
</style>
