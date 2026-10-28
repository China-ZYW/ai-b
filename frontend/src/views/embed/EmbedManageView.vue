<template>
  <div class="embed-manage">
    <div class="page-head">
      <div>
        <h2 class="ph-title">对话嵌入</h2>
        <p class="ph-desc">
          把某个数据源以<b>匿名令牌</b>开放给第三方页面：对方用一段 iframe 代码即可嵌入"对话问数"，访客无需登录。
          令牌只授予"对该数据源提问"这一件事，敏感字段仍按脱敏规则返回。
        </p>
      </div>
      <el-button type="primary" :loading="busy" :disabled="busy" @click="openCreate">新建嵌入</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border size="small">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="数据源" min-width="160">
        <template #default="{ row }">
          <span v-if="!row.datasourceIds || !row.datasourceIds.length" class="muted">—</span>
          <span v-else>
            <span v-for="id in row.datasourceIds" :key="id" class="ds-chip">{{ dsName(row, id) }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="有效期" width="170">
        <template #default="{ row }">{{ row.expireAt || '长期有效' }}</template>
      </el-table-column>
      <el-table-column label="允许站点" min-width="150">
        <template #default="{ row }">{{ row.allowedOrigins || '不限制' }}</template>
      </el-table-column>
      <el-table-column prop="viewCount" label="访问次数" width="100" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="copyCode(row)">复制嵌入代码</el-button>
          <el-button link type="danger" size="small" :disabled="busy" @click="removeRow(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有创建任何嵌入" :image-size="100" />
      </template>
    </el-table>

    <el-dialog v-model="showCreate" title="新建对话问数嵌入" width="520px">
      <el-form label-width="110px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="128" placeholder="如：官网数据助手" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select
            v-model="form.datasourceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="可选择多个数据源"
            style="width: 100%"
          >
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="允许站点">
          <el-input
            v-model="form.allowedOrigins"
            placeholder="如 https://www.example.com，多个用逗号分隔；留空=不限制"
          />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker
            v-model="form.expireAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示长期有效"
            style="width: 100%"
          />
        </el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          title="嵌入会把该数据源开放给外部匿名访问，请确认不含敏感数据；敏感列仍会按脱敏规则返回。"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="busy" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatEmbedApi, type ChatEmbedVO } from '@/api/chatEmbed'
import { datasourceApi } from '@/api/datasource'
import { copyText } from '@/utils/clipboard'

/**
 * 对话问数嵌入管理页（组织内，权限 chat:embed:write）。
 * 列出/创建/撤销嵌入，并生成可直接贴进第三方页面的 iframe 代码。
 */
const list = ref<ChatEmbedVO[]>([])
const datasources = ref<{ id: number; name: string }[]>([])
const loading = ref(false)
const busy = ref(false)
const showCreate = ref(false)
const form = ref<{ name: string; datasourceIds: number[]; allowedOrigins: string; expireAt: string | null }>({
  name: '',
  datasourceIds: [],
  allowedOrigins: '',
  expireAt: null
})

/** 数据源 id → 名称（列表展示用）：优先用后端随行返回的名称，其次回退本地数据源列表。 */
function dsName(row: ChatEmbedVO, id: number): string {
  const fromRow = row.datasourceNames?.[String(id)]
  if (fromRow) return fromRow
  const d = datasources.value.find((x) => x.id === id)
  return d ? d.name : `数据源 ${id}`
}

async function load() {
  loading.value = true
  try {
    const [embeds, ds] = await Promise.all([chatEmbedApi.list(), datasourceApi.list()])
    list.value = embeds
    datasources.value = (ds || []).map((d: any) => ({ id: d.id, name: d.name }))
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  showCreate.value = true
  if (!datasources.value.length) {
    try {
      const ds = await datasourceApi.list()
      datasources.value = (ds || []).map((d: any) => ({ id: d.id, name: d.name }))
    } catch {
      /* 拉不到数据源时用户仍可手动填，不阻塞弹窗 */
    }
  }
}

async function submitCreate() {
  if (!form.value.datasourceIds.length) {
    ElMessage.warning('请至少选择一个数据源')
    return
  }
  busy.value = true
  try {
    await chatEmbedApi.create({
      name: form.value.name,
      datasourceIds: form.value.datasourceIds,
      allowedOrigins: form.value.allowedOrigins,
      expireAt: form.value.expireAt
    })
    ElMessage.success('已创建')
    showCreate.value = false
    form.value = { name: '', datasourceIds: [], allowedOrigins: '', expireAt: null }
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    busy.value = false
  }
}

function embedUrl(token: string) {
  return `${window.location.origin}/embed/chat/${token}`
}

async function copyCode(row: ChatEmbedVO) {
  // 撑满整页写法：min-height:100vh 保证即使放在无高度的容器里也至少占满一屏；
  // height:100% 在父容器有高度时自动跟随。旧写法 height="640" 是固定高度，会留大片空白。
  const code = `<iframe src="${embedUrl(row.token)}" width="100%" height="100%" style="border:0;display:block;min-height:100vh" allow="clipboard-write"></iframe>`
  try {
    await copyText(code)
    ElMessage.success('嵌入代码已复制')
  } catch {
    ElMessage.info(code)
  }
}

async function removeRow(row: ChatEmbedVO) {
  try {
    await ElMessageBox.confirm(`撤销后该嵌入链接立即失效，确定撤销「${row.name || row.token}」吗？`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  busy.value = true
  try {
    await chatEmbedApi.remove(row.id)
    ElMessage.success('已撤销')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '撤销失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.embed-manage { padding: 4px; }
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.ph-title { margin: 0 0 6px; font-size: 18px; }
.ph-desc { margin: 0; color: var(--el-text-color-secondary, #6b7280); font-size: 13px; line-height: 1.6; max-width: 720px; }
.muted { color: var(--el-text-color-secondary, #9ca3af); }
.ds-chip { display: inline-block; margin: 2px; padding: 1px 8px; background: #eff6ff; color: #2563eb; border-radius: 10px; font-size: 12px; white-space: nowrap; }
</style>
