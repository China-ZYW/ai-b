<script setup lang="ts">
/**
 * 图表选择器（P2）：图型下拉 + 字段拖放（X / 系列 / 度量）。
 *
 * <b>图型清单来自后端 {@code /api/completion/chart-types}</b>，不在前端硬编码——
 * 将来渲染层加了新图型，只需后端注册，下拉自动多一项。
 *
 * <b>与只读推荐的关系</b>：后端每次执行都会给出推荐 {@code ChartSpec}；本组件的
 * {@code modelValue} 为 null 时表示"跟随推荐"，用户一旦改动即视为显式指定。
 * 这样"什么都没动"的结果与问数链路完全一致，不会莫名其妙变成用户选的图。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { chartFieldRoles, chartTypes } from '@/api/query'
import type { ChartSpec, QueryResult } from '@/types'
import type { ChartTypeDef } from '@/api/query'

const props = withDefaults(
  defineProps<{
    /** 当前规格；null = 跟随推荐 */
    modelValue: ChartSpec | null
    result: QueryResult | null
    /** 后端推荐（用户未干预时展示） */
    recommended?: ChartSpec | null
    /** 是否允许编辑字段绑定（明细表等不可图化时自动关闭） */
    editable?: boolean
  }>(),
  { recommended: null, editable: true }
)

const emit = defineEmits<{ 'update:modelValue': [ChartSpec | null] }>()

const types = ref<ChartTypeDef[]>([])
const roles = ref<{ code: string; displayName: string; required: boolean }[]>([])

const active = computed<ChartSpec | null>(() => props.modelValue ?? props.recommended ?? null)
const activeType = computed(() => active.value?.type ?? 'TABLE')
const canChart = computed(() => activeType.value !== 'TABLE')

/** 统一补丁出口：始终以"当前规格 + 本次改动"重建，保证 style 等未被触碰的字段不丢。 */
const patch = (p: Partial<ChartSpec>) => {
  const base: ChartSpec = active.value ?? {
    type: (activeType.value ?? 'TABLE') as ChartSpec['type'],
    xField: null,
    yFields: [],
    seriesField: null,
    title: null,
    reason: null
  }
  emit('update:modelValue', { ...base, ...p })
}

const currentType = computed({
  get: () => activeType.value,
  set: (t: string) => patch({ type: t as ChartSpec['type'] })
})

/** 可用度量列（结果里的数值列，粗判：其余列多是维度）。 */
const measureCols = computed(() => props.result?.columns ?? [])
const dimCols = computed(() => props.result?.columns ?? [])

const xField = computed({
  get: () => active.value?.xField ?? '',
  set: (v: string) => patch({ xField: v || null })
})
const seriesField = computed({
  get: () => active.value?.seriesField ?? '',
  set: (v: string) => patch({ seriesField: v || null })
})

async function loadRoles(code: string) {
  try {
    roles.value = await chartFieldRoles(code)
  } catch {
    roles.value = []
  }
}

onMounted(async () => {
  try {
    types.value = (await chartTypes()) ?? []
  } catch {
    types.value = []
  }
  await loadRoles(activeType.value)
})

watch(activeType, (t) => void loadRoles(t))

/** 一键套用推荐：清掉用户干预，回到"跟随推荐"。 */
const resetToRecommend = () => emit('update:modelValue', null)

/** 把某个字段拖/点到指定角色。 */
function assign(role: string, col: string) {
  if (role === 'X') xField.value = col
  else if (role === 'SERIES') seriesField.value = col
  else if (role === 'Y') {
    const ys = active.value?.yFields ?? []
    const next = ys.includes(col) ? ys.filter((y) => y !== col) : [...ys, col]
    patch({ yFields: next })
  }
}
</script>

<template>
  <div v-if="result && result.columns?.length" class="cp">
    <div class="cp-row">
      <span class="cp-lb">图型</span>
      <el-select v-model="currentType" size="small" class="cp-sel" :disabled="!editable">
        <el-option
          v-for="t in types"
          :key="t.code"
          :label="t.displayName"
          :value="t.code"
        />
      </el-select>
      <el-button v-if="modelValue" size="small" text @click="resetToRecommend">恢复推荐</el-button>
      <span v-if="active?.reason" class="cp-why" :title="active.reason">{{ active.reason }}</span>
    </div>

    <div v-if="canChart && editable" class="cp-row cp-wrap">
      <template v-for="r in roles" :key="r.code">
        <div class="cp-slot">
          <span class="cp-lb">{{ r.displayName }}<i v-if="r.required">*</i></span>
          <el-select
            :model-value="r.code === 'Y' ? (active?.yFields ?? [])[0] ?? '' : r.code === 'X' ? xField : seriesField"
            size="small"
            clearable
            class="cp-sel"
            :placeholder="'拖入或选择'"
            @update:model-value="(v: string) => assign(r.code, v)"
          >
            <el-option v-for="c in r.code === 'Y' ? measureCols : dimCols" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
      </template>
    </div>

    <p v-if="canChart && editable" class="cp-tip">
      可用字段：
      <el-tag
        v-for="c in measureCols"
        :key="c"
        size="small"
        class="cp-chip"
        @click="assign('Y', c)"
      >{{ c }}</el-tag>
      <span class="cp-hint">（点击字段即加入度量；「恢复推荐」可回到自动图表）</span>
    </p>
  </div>
</template>

<style scoped>
.cp {
  padding: 8px 0 0;
}
.cp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.cp-wrap {
  flex-wrap: wrap;
}
.cp-lb {
  color: var(--el-text-color-regular, #606266);
  font-size: 12px;
  white-space: nowrap;
}
.cp-lb i {
  color: #f56c6c;
  font-style: normal;
}
.cp-sel {
  width: 168px;
}
.cp-slot {
  display: flex;
  align-items: center;
  gap: 4px;
}
.cp-why {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cp-tip {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.cp-chip {
  cursor: pointer;
  margin-right: 4px;
}
.cp-hint {
  color: var(--el-text-color-placeholder, #c0c4cc);
}
</style>
