<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { historyApi } from '@/api/history'
import ResultPanel from '@/components/chat/ResultPanel.vue'
import { useDatasourceStore } from '@/stores/datasource'
import type { HistoryDetailVO, HistoryVO } from '@/types'
import { datasourceTypeLabel, formatDateTime, formatElapsed, formatNumber } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { usePerm } from '@/composables/usePerm'

const dsStore = useDatasourceStore()
const { can } = usePerm()

const loading = ref(false)
const rows = ref<HistoryVO[]>([])
const total = ref(0)
const query = reactive<{ datasourceId: number | null; limit: number; offset: number }>({
  datasourceId: null,
  limit: 20,
  offset: 0
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await historyApi.list({
      datasourceId: query.datasourceId ?? undefined,
      limit: query.limit,
      offset: query.offset
    })
    rows.value = page.items
    total.value = page.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void dsStore.load().catch(() => undefined)
  void load()
})

function onFilterChange(): void {
  query.offset = 0
  void load()
}

function onPageChange(p: number): void {
  query.offset = (p - 1) * query.limit
  void load()
}

function dsName(id?: number | null): string {
  if (id == null) return '—'
  const hit = dsStore.list.find((d) => d.id === id)
  return hit ? `${hit.name}（${datasourceTypeLabel(hit.type)}）` : `#${id}`
}

/* ---------------- 详情抽屉（回放） ---------------- */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<HistoryDetailVO | null>(null)

/**
 * 打开历史回放：先用列表行兜底展示元数据，再拉回放详情（结果快照/重跑 SQL + 图表 + AI 解读 + 质量校验）。
 * 回放失败（数据源已删/改）时保留元数据兜底，下方模板会展示提示与（若有）当时落库的解读/校验。
 */
async function openDetail(row: HistoryVO): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = { history: row, result: null, chart: null, insight: null, verify: null }
  try {
    detail.value = await historyApi.detailData(row.id)
  } catch {
    // 拦截器已提示；保留列表行元数据兜底
  } finally {
    detailLoading.value = false
  }
}

async function removeRow(row: HistoryVO): Promise<void> {
  try {
    await ElMessageBox.confirm('确定删除这条历史记录吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await historyApi.remove(row.id)
    ElMessage.success('已删除')
    if (rows.value.length === 1 && query.offset > 0) query.offset -= query.limit
    void load()
  } catch {
    // 拦截器已提示
  }
}

async function copySql(sql?: string | null): Promise<void> {
  if (!sql) return
  const ok = await copyText(sql)
  if (ok) ElMessage.success('SQL 已复制')
  else ElMessage.warning('复制失败，请手动选择')
}
</script>

<template>
  <div class="history-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">查询历史</h2>
        <p class="ph-desc">回顾你问过的每一次数据，可查看详情或删除。</p>
      </div>
      <el-select
        :model-value="query.datasourceId"
        placeholder="全部数据源"
        clearable
        style="width: 220px"
        @update:model-value="(v: number | null) => { query.datasourceId = v ?? null; onFilterChange() }"
      >
        <el-option v-for="d in dsStore.list" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
    </div>

    <div class="table-card card">
      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column label="问题" min-width="260">
          <template #default="{ row }">
            <span class="q-text">{{ row.question }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数据源" min-width="160">
          <template #default="{ row }">{{ dsName(row.datasourceId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OK' ? 'success' : 'danger'" size="small" effect="light" round>
              {{ row.status === 'OK' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="90" align="right">
          <template #default="{ row }">{{ formatNumber(row.rowCount) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ formatElapsed(row.elapsedMs) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openDetail(row)">详情</el-button>
            <el-button v-if="can('history:query:delete')" size="small" text type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无历史记录" :image-size="90" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="query.limit"
          :current-page="query.offset / query.limit + 1"
          @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- 详情抽屉（回放：元数据 + 结果/图表 + AI 解读 + 质量校验） -->
    <el-drawer v-model="detailVisible" title="历史回放" size="760px">
      <div v-if="detail" v-loading="detailLoading" class="detail-body">
        <div class="d-field">
          <span class="d-label">问题</span>
          <p class="d-value q">{{ detail.history.question }}</p>
        </div>
        <div class="d-grid">
          <div class="d-field">
            <span class="d-label">数据源</span>
            <span class="d-value">{{ dsName(detail.history.datasourceId) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">状态</span>
            <el-tag :type="detail.history.status === 'OK' ? 'success' : 'danger'" size="small" effect="light" round>
              {{ detail.history.status === 'OK' ? '成功' : '失败' }}
            </el-tag>
          </div>
          <div class="d-field">
            <span class="d-label">行数</span>
            <span class="d-value">{{ formatNumber(detail.history.rowCount) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">耗时</span>
            <span class="d-value">{{ formatElapsed(detail.history.elapsedMs) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">时间</span>
            <span class="d-value">{{ formatDateTime(detail.history.createdAt) }}</span>
          </div>
        </div>
        <div class="d-field">
          <div class="d-label-row">
            <span class="d-label">生成的 SQL</span>
            <el-button size="small" text @click="copySql(detail.history.sql)">
              <el-icon><CopyDocument /></el-icon> 复制
            </el-button>
          </div>
          <pre class="sql-block mono">{{ detail.history.sql || '（无 SQL 记录）' }}</pre>
        </div>

        <!-- 回放主体：结果数据/图表/AI 解读/质量校验一次性还原（复用问数页面板，readonly 隐藏操作入口） -->
        <ResultPanel
          v-if="detail.result"
          :result="detail.result"
          :chart="detail.chart ?? null"
          :insight="detail.insight ?? null"
          :verify="detail.verify ?? null"
          readonly
        />
        <!-- 结果不可回放（数据源已删/改）但当时存过解读/校验：仍单独还原这两块结论 -->
        <template v-else-if="!detailLoading && (detail.insight || detail.verify)">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="结果数据无法回放（数据源可能已删除或变更），以下为问数当时的解读与校验结论"
          />
          <div v-if="detail.insight" class="d-field">
            <span class="d-label">AI 数据解读</span>
            <p v-if="detail.insight.summary" class="d-value">{{ detail.insight.summary }}</p>
            <ul v-if="detail.insight.keyPoints?.length" class="rp-list">
              <li v-for="(p, i) in detail.insight.keyPoints" :key="'k' + i">{{ p }}</li>
            </ul>
          </div>
          <div v-if="detail.verify" class="d-field">
            <span class="d-label">质量校验</span>
            <span class="d-value">
              <el-tag v-if="detail.verify.score != null" size="small" effect="light" round>质量分 {{ detail.verify.score }}</el-tag>
              <span v-if="detail.verify.suggestion" class="rp-sugg">{{ detail.verify.suggestion }}</span>
            </span>
            <ul v-if="detail.verify.issues?.length" class="rp-list">
              <li v-for="(s, i) in detail.verify.issues" :key="'v' + i">{{ s }}</li>
            </ul>
          </div>
        </template>
        <el-alert
          v-else-if="!detailLoading"
          type="info"
          :closable="false"
          show-icon
          title="该历史无可回放的结果数据（数据源可能已删除或变更）"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.history-view {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 32px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
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
}
.table-card {
  padding: 8px 12px 14px;
}
.q-text {
  font-weight: 500;
  color: var(--text-regular);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.d-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.d-label {
  font-size: 12px;
  color: var(--text-placeholder);
  font-weight: 600;
}
.d-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.d-value {
  font-size: 13.5px;
  color: var(--text-primary);
  margin: 0;
}
.d-value.q {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.6;
}
.d-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.sql-block {
  margin: 0;
  padding: 14px 16px;
  background: linear-gradient(135deg, #00164f, #002fa7);
  color: #d6e2ff;
  border-radius: var(--radius-md);
  font-size: 12.5px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.rp-list {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
}
.rp-sugg {
  margin-left: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
