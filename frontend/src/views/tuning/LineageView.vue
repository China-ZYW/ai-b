<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { lineageApi } from '@/api/lineage'
import { useDatasourceStore } from '@/stores/datasource'
import type { DatasourceLineageVO } from '@/types'

/**
 * 问数调优「数据血缘」tab（需求 §3.3 收口）：把分散在「指标管理 / 看板列表 / 语义管理」里的血缘
 * 收拢成一处常驻入口。选数据源后展示三层关系：
 *   字段（COLUMN）→ 引用它的指标 → 这些指标进了哪些看板。
 *
 * 数据来自后端一次聚合的 GET /api/lineage/datasource/{id}（避免前端对字段逐个调 fieldReferences 形成 N+1）。
 * 血缘是增强信息，加载失败不打断浏览。
 */
const props = defineProps<{ datasourceId: number | null }>()

const dsStore = useDatasourceStore()
const data = ref<DatasourceLineageVO | null>(null)
const loading = ref(false)

const dsName = computed(
  () => dsStore.list.find((d) => d.id === props.datasourceId)?.name || '—'
)

async function load(id: number): Promise<void> {
  loading.value = true
  data.value = null
  try {
    data.value = await lineageApi.datasource(id)
  } catch (e) {
    // 血缘是增强信息，失败不打断浏览
    console.error('数据血缘加载失败', e)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.datasourceId,
  (id) => {
    if (id) {
      void load(id)
    } else {
      data.value = null
    }
  },
  { immediate: true }
)

onMounted(() => {
  if (props.datasourceId) {
    void load(props.datasourceId)
  }
})
</script>

<template>
  <div v-loading="loading" class="lineage-view">
    <div class="lv-head">
      <div>
        <h3 class="lv-title">数据血缘总览</h3>
        <p class="lv-desc">
          数据源「<b>{{ dsName }}</b>」下所有字段 → 被哪些指标引用 → 这些指标进了哪些看板。
          点击行首展开查看明细。
        </p>
      </div>
      <div v-if="data" class="lv-stat">
        共 <b>{{ data.fieldCount ?? 0 }}</b> 个字段参与血缘
      </div>
    </div>

    <div v-if="data && (data.fields?.length ?? 0) > 0" class="lv-table-wrap">
      <el-table :data="data.fields || []" row-key="fieldId" border size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="lv-field-detail">
              <template v-if="row.metrics && row.metrics.length">
                <div v-for="m in row.metrics" :key="m.metricId" class="lv-metric">
                  <div class="lv-metric-head">
                    <el-tag size="small" effect="plain" type="primary">{{ m.metricName }}</el-tag>
                    <span class="muted small">被 {{ m.dashboards?.length ?? 0 }} 个看板引用</span>
                  </div>
                  <div v-if="m.dashboards && m.dashboards.length" class="lv-dashboards">
                    <el-tag
                      v-for="d in m.dashboards"
                      :key="d.id"
                      size="small"
                      effect="light"
                      :type="d.isScreen === 1 ? 'warning' : 'info'"
                      class="lv-dash-tag"
                    >{{ d.name }}<span v-if="d.isScreen === 1"> · 大屏</span></el-tag>
                  </div>
                  <div v-else class="muted small">（未被任何看板引用）</div>
                </div>
              </template>
              <div v-else class="muted">暂无指标引用该字段</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="字段" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.column }}</span>
            <span v-if="row.label && row.label !== row.column" class="muted">（{{ row.label }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="来源表" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono small">{{ row.table || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="角色" width="96" align="center">
          <template #default="{ row }"><span class="muted">{{ row.role || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="敏感" width="76" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.sensitive === 1" type="danger" effect="light" size="small" round>敏感</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="引用指标" width="92" align="center">
          <template #default="{ row }"><b>{{ row.metrics?.length ?? 0 }}</b></template>
        </el-table-column>
      </el-table>
    </div>

    <el-empty v-else-if="!loading" description="该数据源暂无字段血缘（指标保存口径后自动生成）" />
  </div>
</template>

<style scoped>
.lineage-view {
  min-height: 200px;
}
.lv-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.lv-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
}
.lv-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 720px;
}
.lv-stat {
  font-size: 13px;
  color: var(--text-secondary);
}
.lv-stat b {
  color: var(--el-color-primary);
}
.lv-table-wrap {
  border-radius: 8px;
  overflow: hidden;
}
.lv-field-detail {
  padding: 6px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.lv-metric {
  border-left: 2px solid var(--el-color-primary-light-5);
  padding-left: 10px;
}
.lv-metric-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.lv-dashboards {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: 2px;
}
.lv-dash-tag {
  font-weight: 400;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.small {
  font-size: 12px;
}
.muted {
  color: var(--text-secondary);
}
</style>
