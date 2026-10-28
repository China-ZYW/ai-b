<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { favoriteApi } from '@/api/favorite'
import { useDatasourceStore } from '@/stores/datasource'
import type { FavoriteVO } from '@/types'
import { datasourceTypeLabel, formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { usePerm } from '@/composables/usePerm'

const router = useRouter()
const dsStore = useDatasourceStore()
const { can } = usePerm()

const loading = ref(false)
const favorites = ref<FavoriteVO[]>([])
/** 展开 SQL 的收藏 id 集合。 */
const expanded = ref<Set<number>>(new Set())

async function load(): Promise<void> {
  loading.value = true
  try {
    favorites.value = await favoriteApi.list()
  } catch {
    favorites.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void dsStore.load().catch(() => undefined)
  void load()
})

function dsName(id?: number | null): string {
  if (id == null) return '未关联数据源'
  const hit = dsStore.list.find((d) => d.id === id)
  return hit ? `${hit.name}（${datasourceTypeLabel(hit.type)}）` : `#${id}`
}

function toggleSql(id: number): void {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
  expanded.value = new Set(expanded.value)
}

async function copySql(sql?: string | null): Promise<void> {
  if (!sql) return
  const ok = await copyText(sql)
  if (ok) ElMessage.success('SQL 已复制')
  else ElMessage.warning('复制失败，请手动选择')
}

/** 去问数：选中该收藏的数据源，并带上问题跳转到问数页。 */
function askAgain(f: FavoriteVO): void {
  if (f.datasourceId != null) dsStore.select(f.datasourceId)
  void router.push({ path: '/app/chat', query: { q: f.question, ds: f.datasourceId ?? undefined } })
}

async function removeFav(f: FavoriteVO): Promise<void> {
  try {
    await ElMessageBox.confirm('确定取消收藏吗？', '提示', {
      type: 'warning',
      confirmButtonText: '取消收藏',
      cancelButtonText: '返回'
    })
  } catch {
    return
  }
  try {
    await favoriteApi.remove(f.id)
    ElMessage.success('已取消收藏')
    void load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="fav-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">我的收藏</h2>
        <p class="ph-desc">收藏常用问法与 SQL，随时一键复问。</p>
      </div>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
    </div>

    <el-empty v-if="!loading && favorites.length === 0" description="还没有收藏，去问数页收藏常用查询吧" />

    <div v-else v-loading="loading" class="fav-grid">
      <div v-for="f in favorites" :key="f.id" class="fav-card card card-hover hairline-top">
        <div class="fav-top">
          <el-icon class="star"><StarFilled /></el-icon>
          <span class="fav-title truncate">{{ f.title || f.question }}</span>
        </div>
        <p class="fav-q">{{ f.question }}</p>
        <div class="fav-meta">
          <span class="meta-item"><el-icon><Coin /></el-icon>{{ dsName(f.datasourceId) }}</span>
          <span class="meta-item"><el-icon><Clock /></el-icon>{{ formatDateTime(f.createdAt) }}</span>
        </div>

        <div v-if="f.sql" class="fav-sql">
          <button class="sql-toggle" @click="toggleSql(f.id)">
            <el-icon><component :is="expanded.has(f.id) ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
            <span>查看 SQL</span>
          </button>
          <pre v-if="expanded.has(f.id)" class="sql-block mono">{{ f.sql }}</pre>
        </div>

        <div class="fav-actions">
          <el-button size="small" type="primary" plain @click="askAgain(f)">
            <el-icon><Promotion /></el-icon> 去问数
          </el-button>
          <el-button v-if="f.sql" size="small" text @click="copySql(f.sql)">
            <el-icon><CopyDocument /></el-icon> 复制 SQL
          </el-button>
          <el-button v-if="can('favorite:item:write')" size="small" text type="danger" @click="removeFav(f)">
            <el-icon><Delete /></el-icon> 取消收藏
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fav-view {
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
.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.fav-card {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.fav-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.star {
  color: var(--warning);
  flex-shrink: 0;
}
.fav-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.fav-q {
  margin: 0;
  font-size: 13px;
  color: var(--text-regular);
  line-height: 1.6;
  background: var(--ikb-ghost);
  padding: 9px 12px;
  border-radius: var(--radius-sm);
}
.fav-meta {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.meta-item .el-icon {
  color: var(--ikb-lighter);
}
.fav-sql {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.sql-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--ikb);
  font-size: 12.5px;
  font-weight: 600;
  padding: 2px 0;
}
.sql-block {
  margin: 8px 0 0;
  padding: 12px 14px;
  background: linear-gradient(135deg, #00164f, #002fa7);
  color: #d6e2ff;
  border-radius: var(--radius-md);
  font-size: 12px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.fav-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  flex-wrap: wrap;
}
</style>
