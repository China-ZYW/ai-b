<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { messageApi } from '@/api/message'
import type { NotifyMessageVO } from '@/types'
import { formatDateTime } from '@/utils/format'

/**
 * 顶栏站内信铃铛：未读角标 + 下拉速览。
 * - 挂载后拉一次未读总数，并每 30s 轮询（轻量，仅打 /messages/unread-count）；
 * - 展开时拉最近 8 条（含已读）供快速预览；点单条即标记已读；「全部已读」一键清；
 * - 「查看全部」跳独立收件箱页 /messages。
 * 失败一律静默（站内信是增强体验，绝不能因它报错打断主流程）。
 */
const router = useRouter()
const unread = ref(0)
const visible = ref(false)
const loading = ref(false)
const recent = ref<NotifyMessageVO[]>([])
const POP_INTERVAL = 30000
let timer: number | undefined

async function refreshCount(): Promise<void> {
  try {
    unread.value = await messageApi.unreadCount()
  } catch {
    /* 静默 */
  }
}

async function refreshRecent(): Promise<void> {
  loading.value = true
  try {
    const res = await messageApi.list({ limit: 8, offset: 0 })
    recent.value = res.items
  } catch {
    recent.value = []
  } finally {
    loading.value = false
  }
}

function onOpen(): void {
  void refreshRecent()
}

async function onRead(id: number): Promise<void> {
  const msg = recent.value.find((m) => m.id === id)
  if (!msg || msg.read) return
  try {
    await messageApi.read(id)
    msg.read = true
    unread.value = Math.max(0, unread.value - 1)
  } catch {
    /* 静默 */
  }
}

async function onReadAll(): Promise<void> {
  try {
    await messageApi.readAll()
    recent.value.forEach((m) => (m.read = true))
    unread.value = 0
    ElMessage.success('已全部标记为已读')
  } catch {
    /* 静默 */
  }
}

function goInbox(): void {
  visible.value = false
  void router.push('/messages')
}

onMounted(() => {
  void refreshCount()
  timer = window.setInterval(refreshCount, POP_INTERVAL)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <el-popover
    v-model:visible="visible"
    placement="bottom-end"
    :width="340"
    trigger="click"
    :show-arrow="false"
    @show="onOpen"
  >
    <template #reference>
      <button class="bell-btn" :class="{ active: unread > 0 }" aria-label="站内信">
        <el-badge :value="unread" :max="99" :hidden="unread === 0" type="danger">
          <el-icon :size="19"><Bell /></el-icon>
        </el-badge>
      </button>
    </template>

    <div class="bell-panel">
      <div class="bp-head">
        <span class="bp-title">站内信</span>
        <button v-if="unread > 0" class="bp-readall" @click="onReadAll">全部已读</button>
      </div>

      <div v-loading="loading" class="bp-list">
        <div v-if="recent.length === 0 && !loading" class="bp-empty">暂无消息</div>
        <div
          v-for="m in recent"
          :key="m.id"
          class="bp-item"
          :class="{ unread: !m.read }"
          @click="onRead(m.id)"
        >
          <span class="bp-dot" :class="(m.severity || 'INFO').toLowerCase()" />
          <div class="bp-body">
            <div class="bp-row">
              <span class="bp-name">{{ m.title || '通知' }}</span>
              <span class="bp-time">{{ formatDateTime(m.createdAt) }}</span>
            </div>
            <div class="bp-text">{{ m.content }}</div>
          </div>
        </div>
      </div>

      <div class="bp-foot">
        <button class="bp-all" @click="goInbox">查看全部</button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.bell-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  transition: background 0.2s ease;
  outline: none;
}
.bell-btn:hover,
.bell-btn.active {
  background: var(--ikb-ghost);
}
.bell-btn.active {
  color: var(--ikb);
}

/* ---- 下拉面板 ---- */
.bell-panel {
  display: flex;
  flex-direction: column;
  max-height: 420px;
}
.bp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 10px;
  border-bottom: 1px solid var(--border-light);
}
.bp-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}
.bp-readall {
  border: none;
  background: transparent;
  color: var(--ikb);
  font-size: 12.5px;
  cursor: pointer;
  padding: 2px 4px;
}
.bp-readall:hover {
  text-decoration: underline;
}
.bp-list {
  flex: 1;
  overflow-y: auto;
  min-height: 80px;
}
.bp-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--text-placeholder);
  font-size: 13px;
}
.bp-item {
  display: flex;
  gap: 10px;
  padding: 10px 6px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.bp-item:hover {
  background: var(--ikb-ghost);
}
.bp-item.unread {
  background: var(--ikb-pale);
}
.bp-item.unread:hover {
  background: #dde7ff;
}
.bp-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  background: #c0c6d4;
}
.bp-dot.info {
  background: #409eff;
}
.bp-dot.warn {
  background: #e6a23c;
}
.bp-dot.critical {
  background: #f56c6c;
}
.bp-body {
  flex: 1;
  min-width: 0;
}
.bp-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.bp-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bp-time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-placeholder);
}
.bp-text {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
  /* 保留正文换行：多行报表按原换行截 2 行（首行为标题），而非拉成一行乱码 */
  white-space: pre-line;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.bp-foot {
  padding-top: 8px;
  border-top: 1px solid var(--border-light);
  text-align: center;
}
.bp-all {
  border: none;
  background: transparent;
  color: var(--ikb);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 8px;
}
.bp-all:hover {
  text-decoration: underline;
}
</style>
