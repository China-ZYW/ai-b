<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { messageApi } from '@/api/message'
import type { NotifyMessageVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { useRowBusy } from '@/utils/actionGuard'

/**
 * 站内信收件箱（/messages，权限点 message:inbox:read）。
 *
 * 全量列表 + 分页 + "只看未读"过滤；单条标记已读 / 删除 / 全部已读。
 * 后端按当前登录者身份强制收敛（uid + tenantId），本页只读"自己的"信。
 * 数据来自告警规则 INAPP 渠道命中时写入的 notify_message，也可能有平台侧直接下发的站内信。
 */
const loading = ref(false)
const list = ref<NotifyMessageVO[]>([])
const total = ref(0)
const unread = ref(0)
const limit = ref(20)
const offset = ref(0)
const onlyUnread = ref(false)
const readAllBusy = ref(false)
const rowBusy = useRowBusy<number>()

async function load(): Promise<void> {
  loading.value = true
  try {
    const [res, cnt] = await Promise.all([
      messageApi.list({ unreadOnly: onlyUnread.value, limit: limit.value, offset: offset.value }),
      messageApi.unreadCount()
    ])
    list.value = res.items
    total.value = res.total
    unread.value = cnt
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange(): void {
  offset.value = 0
  void load()
}

async function onRead(id: number): Promise<void> {
  const m = list.value.find((x) => x.id === id)
  if (!m || m.read) return
  await rowBusy.run(id, async () => {
    try {
      await messageApi.read(id)
      m.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch {
      /* 拦截器已提示 */
    }
  })
}

async function onRemove(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确定删除这条站内信吗？删除后不可恢复。', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await rowBusy.run(id, async () => {
    try {
      await messageApi.remove(id)
      ElMessage.success('已删除')
      await load()
    } catch {
      /* 拦截器已提示 */
    }
  })
}

async function onReadAll(): Promise<void> {
  readAllBusy.value = true
  try {
    await messageApi.readAll()
    ElMessage.success('已全部标记为已读')
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    readAllBusy.value = false
  }
}

function onPage(page: number): void {
  offset.value = (page - 1) * limit.value
  void load()
}

function severityTag(s?: string | null): 'info' | 'warning' | 'danger' {
  if (s === 'CRITICAL') return 'danger'
  if (s === 'WARN') return 'warning'
  return 'info'
}
function severityLabel(s?: string | null): string {
  if (s === 'CRITICAL') return '严重'
  if (s === 'WARN') return '警告'
  return '提示'
}

/** 列表行摘要：只展示正文首行（定时报表等多行正文在单元里拉成一行不可读），全文看展开行。 */
function contentSummary(content?: string | null): string {
  if (!content) return '—'
  const first = content.split('\n')[0].trim()
  return first || '—'
}

const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="inbox-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">站内信</h2>
        <p class="ph-desc">
          来自智能预警等系统的站内通知。告警规则选择「站内信」渠道且命中阈值时，会按规则指定的接收人写入此处。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button :loading="readAllBusy" :disabled="unread === 0" @click="onReadAll">
          <el-icon><Check /></el-icon> 全部已读
        </el-button>
      </div>
    </div>

    <div class="toolbar card">
      <div class="tb-left">
        <span class="tb-total">共 {{ total }} 条</span>
        <el-tag v-if="unread > 0" type="danger" size="small" effect="light" round>
          未读 {{ unread }}
        </el-tag>
      </div>
      <el-checkbox v-model="onlyUnread" @change="onFilterChange">只看未读</el-checkbox>
    </div>

    <div class="card list-card">
      <el-table v-loading="loading" :data="list" height="520" size="small" border stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre v-if="row.content" class="msg-pre">{{ row.content }}</pre>
            <span v-else class="msg-pre-empty">（无正文）</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="46" align="center">
          <template #default="{ row }">
            <span v-if="!row.read" class="unread-dot" title="未读" />
            <span v-else class="read-dot" title="已读" />
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="severityTag(row.severity)" size="small" effect="light" round>
              {{ severityLabel(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="msg-title" :class="{ unread: !row.read }">{{ row.title || '通知' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="320" show-overflow-tooltip>
          <template #default="{ row }">{{ contentSummary(row.content) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="!row.read"
              size="small"
              text
              :loading="rowBusy.isBusy(row.id)"
              @click="onRead(row.id)"
            >
              <el-icon><Check /></el-icon> 标记已读
            </el-button>
            <el-button
              size="small"
              text
              type="danger"
              :loading="rowBusy.isBusy(row.id)"
              @click="onRemove(row.id)"
            >
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="收件箱空空如也" :image-size="90" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="total"
          :page-size="limit"
          :current-page="currentPage"
          @current-change="onPage"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.inbox-view {
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
  max-width: 720px;
  line-height: 1.6;
}
.ph-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  margin-bottom: 14px;
}
.tb-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tb-total {
  font-size: 13px;
  color: var(--text-secondary);
}
.list-card {
  padding: 6px 14px 14px;
}
.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
}
.read-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c8cdda;
}
.msg-title {
  font-weight: 500;
  color: var(--text-regular);
}
.msg-title.unread {
  font-weight: 700;
  color: var(--text-primary);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
/* 展开行：等宽字体完整呈现纯文本报表（渲染器的列对齐依赖等宽） */
.msg-pre {
  margin: 6px 16px;
  padding: 10px 14px;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre;
  overflow-x: auto;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 6px;
  max-height: 420px;
  overflow-y: auto;
}
.msg-pre-empty {
  display: inline-block;
  margin: 8px 16px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
