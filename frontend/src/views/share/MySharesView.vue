<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { shareApi } from '@/api/share'
import { dashboardApi } from '@/api/dashboard'
import type { ShareVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { usePerm } from '@/composables/usePerm'

const { can } = usePerm()
const loading = ref(false)
const shares = ref<ShareVO[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    shares.value = await shareApi.list()
  } catch {
    shares.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 完整可访问链接（后端 url 可能是相对路径）。 */
function fullUrl(s: ShareVO): string {
  if (!s.url) {
    const path = s.type === 'dashboard' ? `/share/screen/${s.token}` : `/s/${s.token}`
    return `${window.location.origin}${path}`
  }
  return s.url.startsWith('http') ? s.url : `${window.location.origin}${s.url}`
}

function isExpired(s: ShareVO): boolean {
  if (!s.expireAt) return false
  const t = new Date(s.expireAt).getTime()
  return !Number.isNaN(t) && t < Date.now()
}

const activeCount = computed(() => shares.value.filter((s) => !isExpired(s)).length)

async function copyUrl(s: ShareVO): Promise<void> {
  const ok = await copyText(fullUrl(s))
  if (ok) ElMessage.success('链接已复制')
  else ElMessage.warning('复制失败，请手动选择')
}

function openShare(s: ShareVO): void {
  window.open(fullUrl(s), '_blank', 'noopener')
}

async function removeShare(s: ShareVO): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后该分享链接将立即失效，确定删除吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    if (s.type === 'dashboard') {
      // 驾驶舱分享存于 dashboard 表：关闭其公开访问即失效，不删大屏本身
      await dashboardApi.configShare(s.id, { enabled: false })
    } else {
      await shareApi.remove(s.id)
    }
    ElMessage.success('已删除')
    void load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="shares-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">分享管理</h2>
        <p class="ph-desc">
          已创建 {{ shares.length }} 个分享，其中 {{ activeCount }} 个有效。管理访问链接、密码与有效期。
        </p>
      </div>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
    </div>

    <el-empty v-if="!loading && shares.length === 0" description="还没有分享，去问数页把结果分享出去吧" />

    <div v-else v-loading="loading" class="share-list">
      <div v-for="s in shares" :key="s.id" class="share-card card card-hover">
        <div class="sc-main">
          <div class="sc-title-row">
            <el-icon class="sc-icon"><Share /></el-icon>
            <el-tag v-if="s.type === 'dashboard'" type="warning" size="small" effect="plain" round>驾驶舱</el-tag>
            <el-tag v-else type="info" size="small" effect="plain" round>指标</el-tag>
            <span class="sc-q truncate">{{ s.question }}</span>
            <el-tag v-if="isExpired(s)" type="info" size="small" effect="plain" round>已过期</el-tag>
            <el-tag v-else type="success" size="small" effect="light" round>有效</el-tag>
          </div>

          <div class="sc-url mono truncate" :title="fullUrl(s)">{{ fullUrl(s) }}</div>

          <div class="sc-meta">
            <span class="meta-item">
              <el-icon><View /></el-icon>{{ s.viewCount ?? 0 }} 次浏览
            </span>
            <span class="meta-item">
              <el-icon><Lock /></el-icon>{{ s.hasPassword ? '有密码' : '无密码' }}
            </span>
            <span class="meta-item">
              <el-icon><Stamp /></el-icon>{{ s.watermark ? '含水印' : '无水印' }}
            </span>
            <span class="meta-item">
              <el-icon><Clock /></el-icon>{{ s.expireAt ? `到期 ${formatDateTime(s.expireAt)}` : '永久有效' }}
            </span>
            <span class="meta-item">
              <el-icon><Calendar /></el-icon>创建于 {{ formatDateTime(s.createdAt) }}
            </span>
          </div>
        </div>

        <div class="sc-actions">
          <el-button size="small" type="primary" plain @click="copyUrl(s)">
            <el-icon><CopyDocument /></el-icon> 复制链接
          </el-button>
          <el-button size="small" text @click="openShare(s)">
            <el-icon><TopRight /></el-icon> 打开
          </el-button>
          <el-button v-if="can('share:link:delete')" size="small" text type="danger" @click="removeShare(s)">
            <el-icon><Delete /></el-icon> 删除
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shares-view {
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
.share-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.share-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 16px 20px;
}
.sc-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sc-title-row {
  display: flex;
  align-items: center;
  gap: 9px;
}
.sc-icon {
  color: var(--ikb);
  flex-shrink: 0;
}
.sc-q {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  max-width: 560px;
}
.sc-url {
  font-size: 12px;
  color: var(--ikb-light);
  background: var(--ikb-ghost);
  padding: 7px 11px;
  border-radius: var(--radius-sm);
}
.sc-meta {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta-item .el-icon {
  color: var(--ikb-lighter);
}
.sc-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
</style>
