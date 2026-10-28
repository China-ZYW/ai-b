<script setup lang="ts">
/**
 * 我的定时报表管理页（T12-1）。列表 + 启停 + 编辑 + 删除 + 立即跑一次。
 *
 * 数据边界：后端只返回当前账号自己的报表（归属唯一），故本页无"别人的任务"形态；
 * 操作权限复用 chat:query:execute（PermGuard 兜 UI）。门控 ASK/schedule 关闭时
 * 整页降级为提示——后端同步拒用（40301），这里只是不让用户点了才知道。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '@/api/report'
import { useConfigStore } from '@/stores/config'
import PermGuard from '@/components/PermGuard.vue'
import ReportFormDialog from './ReportFormDialog.vue'
import type { ScheduledReportVO } from '@/types'

const config = useConfigStore()
const router = useRouter()

const rows = ref<ScheduledReportVO[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editRow = ref<ScheduledReportVO | null>(null)

const FREQ_LABEL: Record<string, string> = { DAILY: '每天', WEEKLY: '每周', HOURLY: '每小时' }
const CH_LABEL: Record<string, string> = { INAPP: '站内信', EMAIL: '邮件', BOTH: '站内+邮件' }
const WEEK_LABEL: Record<string, string> = {
  '1': '一', '2': '二', '3': '三', '4': '四', '5': '五', '6': '六', '7': '日'
}

const freqText = (r: ScheduledReportVO): string => {
  if (r.freq === 'HOURLY') return '每小时整点后'
  const days =
    r.freq === 'WEEKLY'
      ? ' ' + (r.weekdays ?? '').split(',').filter(Boolean).map((d) => WEEK_LABEL[d.trim()] ?? d).join('/')
      : ''
  return `${FREQ_LABEL[r.freq] ?? r.freq}${days} ${r.runTime ?? ''}`.trim()
}

const statusType = (s?: string | null) =>
  s === 'OK' ? 'success' : s === 'FAILED' ? 'danger' : s === 'SKIPPED' ? 'warning' : 'info'
const statusText = (s?: string | null) =>
  s === 'OK' ? '成功' : s === 'FAILED' ? '失败' : s === 'SKIPPED' ? '已跳过' : '未执行'

const timeText = (v?: string | null) => (v ? v.replace('T', ' ').slice(0, 16) : '—')

const load = async () => {
  loading.value = true
  try {
    rows.value = (await reportApi.list()) ?? []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editRow.value = null
  dialogVisible.value = true
}
const openEdit = (r: ScheduledReportVO) => {
  editRow.value = r
  dialogVisible.value = true
}

/** 新建查询模板跳高级查询（模板唯一创建入口）。 */
const goCreateTemplate = () => router.push('/app/sql')

/** 启停：PUT 是覆盖式全字段更新，故用行数据原样回传、只翻转 enabled。 */
const onToggle = async (r: ScheduledReportVO) => {
  try {
    await reportApi.update(r.id, {
      name: r.name,
      templateId: r.templateId,
      paramsJson: r.paramsJson ?? null,
      freq: r.freq,
      runTime: r.runTime ?? null,
      weekdays: r.weekdays ?? null,
      channel: r.channel,
      inappUids: r.inappUids ?? null,
      emailTargets: r.emailTargets ?? null,
      enabled: r.enabled
    })
    ElMessage.success(r.enabled ? '已启用' : '已停用')
  } catch {
    r.enabled = !r.enabled // 服务端失败回滚开关外观
  }
}

const runningId = ref<number | null>(null)
const onRunNow = async (r: ScheduledReportVO) => {
  runningId.value = r.id
  try {
    const status = await reportApi.runNow(r.id)
    if (status === 'OK') ElMessage.success('已执行并推送')
    else if (status === 'SKIPPED') ElMessage.warning('本轮跳过（模板不可用），详见上轮注记')
    else ElMessage.error('本轮执行失败，详见上轮注记')
    await load()
  } catch {
    // 全局拦截器已提示
  } finally {
    runningId.value = null
  }
}

const onDelete = async (r: ScheduledReportVO) => {
  try {
    await ElMessageBox.confirm(`删除定时报表「${r.name}」？只删调度任务，不影响来源模板。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await reportApi.remove(r.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(() => {
  config.load()
  load()
})
</script>

<template>
  <div class="rp-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">定时报表</h2>
        <p class="ph-desc">把保存的查询模板按周期自动执行，结果推送到站内信或邮件（正文内嵌前 20 行表格）。</p>
      </div>
      <PermGuard code="chat:query:execute" mode="disable" tip="当前角色无执行权限">
        <div class="rp-head-btns">
          <el-button class="rp-tpl-btn" @click="goCreateTemplate">
            <el-icon><Document /></el-icon> 新建查询模板
          </el-button>
          <el-button v-if="config.scheduleEnabled" type="primary" class="add-btn" @click="openCreate">
            <el-icon><Plus /></el-icon> 新建报表任务
          </el-button>
        </div>
      </PermGuard>
    </div>

    <el-alert
      v-if="!config.scheduleEnabled"
      type="warning"
      show-icon
      :closable="false"
      title="定时报表功能未启用（配置项 ASK / schedule），请联系管理员开启"
      class="rp-off"
    />

    <el-card v-else shadow="never">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column prop="name" label="报表名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="templateName" label="来源模板" min-width="140" show-overflow-tooltip />
        <el-table-column label="频率" min-width="130">
          <template #default="{ row }">{{ freqText(row) }}</template>
        </el-table-column>
        <el-table-column label="渠道" width="100">
          <template #default="{ row }">{{ CH_LABEL[row.channel] ?? row.channel }}</template>
        </el-table-column>
        <el-table-column label="上次执行" min-width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.lastStatus)">{{ statusText(row.lastStatus) }}</el-tag>
            <span class="rp-time">{{ timeText(row.lastRunAt) }}</span>
            <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
              <span class="rp-err">注</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" size="small" @change="onToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :loading="runningId === row.id" @click="onRunNow(row)">
              立即跑
            </el-button>
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="rp-empty">
            还没有定时报表任务——先
            <el-button size="small" text type="primary" class="rp-empty-link" @click="goCreateTemplate">新建查询模板</el-button>
            ，再点「新建报表任务」设为定时推送
          </div>
        </template>
      </el-table>
    </el-card>

    <ReportFormDialog v-model="dialogVisible" :edit="editRow" @saved="load" />
  </div>
</template>

<style scoped>
.rp-view {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 32px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
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
.add-btn {
  background: var(--grad-btn);
  border: none;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}
.rp-head-btns {
  display: flex;
  gap: 10px;
  align-items: center;
}
.rp-empty-link {
  margin: 0 4px;
  vertical-align: baseline;
}
.rp-off {
  margin-bottom: 16px;
}
.rp-time {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.rp-err {
  margin-left: 6px;
  font-size: 11.5px;
  color: var(--el-color-danger);
  cursor: default;
}
.rp-empty {
  padding: 28px 0;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
