<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dashboardApi, dashboardGroupApi } from '@/api/dashboard'
import { metricApi } from '@/api/metric'
import { lineageApi } from '@/api/lineage'
import { datasourceApi } from '@/api/datasource'
import TemplateGallery from '@/components/dashboard/TemplateGallery.vue'
import DashboardShareDialog from '@/components/dashboard/DashboardShareDialog.vue'
import { fetchThemes, resolveTheme, themeOptions, themeVersion } from '@/theme/dashboardTheme'
import type {
  DashboardGroupForm,
  DashboardGroupVO,
  DashboardLineageVO,
  DashboardMoveForm,
  DashboardTemplateVO,
  DashboardVO,
  DataSourceVO,
  MetricVO
} from '@/types'
import { formatDateTime } from '@/utils/format'
import { usePerm } from '@/composables/usePerm'
import { useConfigStore } from '@/stores/config'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 驾驶舱列表：左侧「多层级分组」目录树 + 右侧卡片网格。
 * 支持三种新建方式（空白 / 一键自动布局 / 从模板），卡片可进入编辑器、大屏预览、删除、移动分组。
 * 分组为多级目录树（parentId 成树），支持新建 / 重命名 / 移动子树 / 删除（仅空分组），看板归属可改。
 */
const router = useRouter()
const { can } = usePerm()
const config = useConfigStore()

const loading = ref(false)
const dashboards = ref<DashboardVO[]>([])
const metrics = ref<MetricVO[]>([])
const groups = ref<DashboardGroupVO[]>([])
/** 主题选择器选项（内置 + 远端自定义）；fetchThemes 完成后刷新。 */
const themeOpts = ref(themeOptions())

/** 左侧目录树当前选中节点：'all'=全部，'ungrouped'=未分组，其余为真实分组 id。 */
const selectedKey = ref<string | number>('all')

/** 分组目录树节点（含两个合成根节点）。 */
interface GroupTreeNode {
  id: number | string
  name: string
  parentId: number | null
  children: GroupTreeNode[]
  isSpecial?: boolean
}

const treeData = computed<GroupTreeNode[]>(() => {
  const nodes = new Map<number, GroupTreeNode>()
  for (const g of groups.value) {
    nodes.set(g.id, { id: g.id, name: g.name, parentId: g.parentId ?? null, children: [] })
  }
  const roots: GroupTreeNode[] = []
  for (const n of nodes.values()) {
    const pid = n.parentId
    if (pid != null && nodes.has(pid)) nodes.get(pid)!.children.push(n)
    else roots.push(n)
  }
  const sortNodes = (list: GroupTreeNode[]) => {
    list.sort((a, b) => (a.id as number) - (b.id as number))
    list.forEach((n) => sortNodes(n.children))
  }
  sortNodes(roots)
  return [
    { id: 'all', name: '全部驾驶舱', parentId: null, children: [], isSpecial: true },
    ...roots,
    { id: 'ungrouped', name: '未分组', parentId: null, children: [], isSpecial: true }
  ]
})

/** 分组 id → 名称（卡片上展示所属分组）。 */
const groupNameMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  groups.value.forEach((g) => (m[g.id] = g.name))
  return m
})

/** 当前选中的真实分组 id（仅新建空白驾驶舱时用于预设归属）。 */
function currentGroupId(): number | null {
  return typeof selectedKey.value === 'number' ? selectedKey.value : null
}

/** 某分组的全部后代 id（含自身），用于「点父节点显示整棵子树下的看板」。 */
function descendantGroupIds(id: number): number[] {
  const result: number[] = [id]
  const stack = [id]
  while (stack.length) {
    const cur = stack.pop() as number
    for (const g of groups.value) {
      if (g.parentId === cur) {
        result.push(g.id)
        stack.push(g.id)
      }
    }
  }
  return result
}

/** 按选中节点过滤看板。 */
const visibleDashboards = computed<DashboardVO[]>(() => {
  if (selectedKey.value === 'all') return dashboards.value
  if (selectedKey.value === 'ungrouped') return dashboards.value.filter((d) => !d.groupId)
  const ids = new Set(descendantGroupIds(selectedKey.value as number))
  return dashboards.value.filter((d) => d.groupId != null && ids.has(d.groupId))
})

/** 移动看板下拉用的扁平分组（带缩进）。 */
const flatGroupOptions = computed<{ id: number; name: string; indent: string }[]>(() => {
  const out: { id: number; name: string; indent: string }[] = []
  const walk = (list: GroupTreeNode[], depth: number) => {
    for (const n of list) {
      if (n.isSpecial) continue
      out.push({ id: n.id as number, name: n.name, indent: depth > 0 ? '　'.repeat(depth) : '' })
      walk(n.children, depth + 1)
    }
  }
  walk(treeData.value.filter((n) => !n.isSpecial), 0)
  return out
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const [ds, ms] = await Promise.all([dashboardApi.list(), metricApi.list()])
    dashboards.value = ds
    metrics.value = ms
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
  // 分组独立加载：老后端（无 /api/dashboard/group）或接口异常时退化为「全部 / 未分组」两种视图，
  // 不把整个列表拖成空白页（本页是「前端可先于后端发布」的兼容点）。
  try {
    groups.value = await dashboardGroupApi.list()
  } catch {
    groups.value = []
  }
}
onMounted(async () => {
  await load()
  await fetchThemes()
  themeOpts.value = themeOptions()
})

function onNodeClick(data: GroupTreeNode): void {
  selectedKey.value = data.id
}

/* ---------------- 看板血缘（P1） ---------------- */
const lineageVisible = ref(false)
const lineageLoading = ref(false)
const lineageData = ref<DashboardLineageVO | null>(null)

async function openLineage(d: DashboardVO): Promise<void> {
  if (d.id == null) return
  lineageVisible.value = true
  lineageLoading.value = true
  lineageData.value = null
  try {
    lineageData.value = await lineageApi.dashboard(d.id)
  } catch (e: any) {
    ElMessage.error(e?.message || '血缘加载失败')
  } finally {
    lineageLoading.value = false
  }
}

/* ---------------- 数据新鲜度（P2）：看板级聚合标黄 ---------------- */
function dashboardFreshness(d: DashboardVO): { stale: boolean; text: string; tip: string } {
  const ids = new Set(
    (d.layout || []).map((w) => w.metricId).filter((x): x is number => typeof x === 'number')
  )
  if (ids.size === 0 || metrics.value.length === 0) {
    return { stale: false, text: '', tip: '' }
  }
  const refs = metrics.value.filter((m) => m.id != null && ids.has(m.id))
  if (refs.length === 0) return { stale: false, text: '', tip: '' }

  const staleOnes = refs.filter((m) => m.stale)
  const ages = refs.map((m) => m.ageHours ?? -1).filter((h) => h >= 0)
  const maxAge = ages.length ? Math.max(...ages) : -1
  const ageText =
    maxAge < 0 ? '尚未取数' : maxAge < 1 ? '刚刚' : maxAge < 24 ? `${maxAge} 小时前` : `${Math.floor(maxAge / 24)} 天前`
  const names = staleOnes.slice(0, 5).map((m) => m.alias || m.name).join('、')
  const more = staleOnes.length > 5 ? ` 等 ${staleOnes.length} 个` : ''
  return {
    stale: staleOnes.length > 0,
    text: staleOnes.length > 0 ? `数据已过期 · ${staleOnes.length}/${refs.length} 个指标` : `数据 ${ageText}`,
    tip:
      staleOnes.length > 0
        ? `以下指标数据已超新鲜度期限：${names}${more}。建议到指标库触发刷新后再看这块看板。`
        : `引用的 ${refs.length} 个指标均在新鲜度内（最旧 ${ageText}）`
  }
}

async function removeDashboard(d: DashboardVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除驾驶舱「${d.name}」吗？此操作不可撤销。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await dashboardApi.remove(d.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

/** 移动看板到指定分组（groupId 空=移出分组）。 */
async function moveDashboard(d: DashboardVO, groupId: number | null): Promise<void> {
  try {
    const payload: DashboardMoveForm = { groupId }
    await dashboardApi.moveGroup(d.id, payload)
    ElMessage.success('已更新分组')
    await load()
  } catch {
    // 拦截器已提示
  }
}

function openScreen(d: DashboardVO): void {
  const url = router.resolve(`/screen/${d.id}`).href
  window.open(url, '_blank')
}

/* ---------------- 大屏分享（问题2） ---------------- */
const shareVisible = ref(false)
const shareTarget = ref<{ id: number | null; name: string }>({ id: null, name: '' })

function openShare(d: DashboardVO): void {
  shareTarget.value = { id: d.id, name: d.name }
  shareVisible.value = true
}

/** 卡片封面按主题预设取背景 / 文字色（问题5：主题可视化）。 */
function coverStyle(key?: string | null) {
  void themeVersion.value // 响应式依赖：远端主题 hydration 后封面自动重算
  const t = resolveTheme(key)
  return { background: t.screenBg, color: t.textSecondary }
}

/** 主题展示名（随远端主题 hydration 重算）。 */
function themeLabel(key?: string | null): string {
  void themeVersion.value
  return resolveTheme(key).label
}

/* ---------------- 分组管理 ---------------- */
const groupDialogVisible = ref(false)
const groupFormMode = ref<'create' | 'edit'>('create')
const groupEditId = ref<number | null>(null)
const groupForm = reactive<{ name: string; parentId: number | null }>({ name: '', parentId: null })
const { busy: groupSubmitting, run: runGroupSubmit } = useActionGuard()

/** 上级分组可选列表：编辑时排除自身及后代（防成环），并含「根级」选项。 */
const groupParentOptions = computed<{ id: number; name: string }[]>(() => {
  const exclude = new Set<number>()
  if (groupFormMode.value === 'edit' && groupEditId.value != null) {
    for (const id of descendantGroupIds(groupEditId.value)) exclude.add(id)
  }
  return groups.value.filter((g) => !exclude.has(g.id)).map((g) => ({ id: g.id, name: g.name }))
})

function openCreateGroup(parentId: number | null): void {
  groupFormMode.value = 'create'
  groupEditId.value = null
  groupForm.name = ''
  groupForm.parentId = parentId
  groupDialogVisible.value = true
}

function openEditGroup(node: GroupTreeNode): void {
  groupFormMode.value = 'edit'
  groupEditId.value = node.id as number
  groupForm.name = node.name
  groupForm.parentId = node.parentId
  groupDialogVisible.value = true
}

async function submitGroup(): Promise<void> {
  const name = groupForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  await runGroupSubmit(async () => {
    try {
      if (groupFormMode.value === 'create') {
        const payload: DashboardGroupForm = { name, parentId: groupForm.parentId }
        await dashboardGroupApi.create(payload)
        ElMessage.success('分组已创建')
      } else {
        const payload: DashboardGroupForm = { name, parentId: groupForm.parentId }
        await dashboardGroupApi.update(groupEditId.value!, payload)
        ElMessage.success('分组已更新')
      }
      groupDialogVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function removeGroup(node: GroupTreeNode): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除分组「${node.name}」吗？仅空分组（无子分组、无驾驶舱）可删除，有成员时请先移出。`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await dashboardGroupApi.remove(node.id as number)
    ElMessage.success('已删除')
    // 若正停留在被删分组，回退到「全部」
    if (selectedKey.value === node.id) selectedKey.value = 'all'
    await load()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 新建驾驶舱 ---------------- */
/**
 * 新建时的默认大屏主题：平台/租户配置（THEME / default-dashboard-theme）优先，
 * 未配置回落内置深色。
 *
 * <p>用函数而不是常量：配置 store 是懒加载的（应用启动时才拉），若在创建表单初始化时就取一次
 * 快照，页面先渲染、配置后到的情况下会永远停在兜底值上。
 */
function defaultTheme(): string {
  return config.defaultDashboardTheme || 'dark'
}

type CreateMode = 'blank' | 'auto' | 'template'
const createVisible = ref(false)
const createMode = ref<CreateMode>('blank')
const { busy: submitting, run: runSubmit } = useActionGuard()
const pickedTemplate = ref<DashboardTemplateVO | null>(null)

const form = reactive<{
  name: string
  description: string
  theme: string
  refreshSeconds: number
  isScreen: number
  metricIds: number[]
}>({
  name: '',
  description: '',
  theme: defaultTheme(),
  refreshSeconds: 30,
  isScreen: 0,
  metricIds: []
})

function openCreate(): void {
  createMode.value = 'blank'
  pickedTemplate.value = null
  Object.assign(form, {
    name: '',
    description: '',
    theme: defaultTheme(),
    refreshSeconds: 30,
    isScreen: 0,
    metricIds: []
  })
  createVisible.value = true
}

function onPickTemplate(t: DashboardTemplateVO): void {
  pickedTemplate.value = t
  if (!form.name) form.name = t.name
  form.theme = t.theme || defaultTheme()
}

function switchMode(m: CreateMode): void {
  createMode.value = m
  if (m !== 'template') pickedTemplate.value = null
}

async function submitCreate(): Promise<void> {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请输入驾驶舱名称')
    return
  }
  if (createMode.value === 'auto' && form.metricIds.length === 0) {
    ElMessage.warning('自动布局请至少选择一个指标')
    return
  }
  if (createMode.value === 'template' && !pickedTemplate.value) {
    ElMessage.warning('请选择一个内置模板')
    return
  }
  await runSubmit(async () => {
    try {
      let vo: DashboardVO
      if (createMode.value === 'auto') {
        vo = await dashboardApi.autoDesign({
          name,
          metricIds: form.metricIds,
          description: form.description.trim() || null,
          theme: form.theme,
          refreshSeconds: form.refreshSeconds,
          isScreen: form.isScreen
        })
      } else if (createMode.value === 'template' && pickedTemplate.value) {
        vo = await dashboardApi.fromTemplate({
          name,
          templateCode: pickedTemplate.value.code,
          metricIds: form.metricIds.length ? form.metricIds : null,
          description: form.description.trim() || null,
          theme: form.theme,
          refreshSeconds: form.refreshSeconds,
          isScreen: form.isScreen
        })
      } else {
        vo = await dashboardApi.create({
          name,
          description: form.description.trim() || null,
          theme: form.theme,
          cols: 12,
          refreshSeconds: form.refreshSeconds,
          isScreen: form.isScreen,
          layout: [],
          // 在当前选中的真实分组下新建时，预设归属（移动子树靠分组树自身，看板归属走专用接口）
          groupId: currentGroupId()
        })
      }
      ElMessage.success('驾驶舱已创建')
      createVisible.value = false
      await load()
      void router.push(`/dashboards/${vo.id}/edit`)
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 驾驶舱 AI 自动化大屏（Skill） ---------------- */
const aiBuildVisible = ref(false)
const dsOptions = ref<DataSourceVO[]>([])
const pickedDs = ref<number | null>(null)
const aiIntent = ref('')
const { busy: building, run: runAiBuild } = useActionGuard()

/** 打开「生成 AI 大屏」对话框：先拉数据源列表供用户选择。 */
async function openAiBuild(): Promise<void> {
  aiIntent.value = ''
  pickedDs.value = null
  try {
    dsOptions.value = await datasourceApi.list()
  } catch {
    dsOptions.value = []
  }
  aiBuildVisible.value = true
}

/** 基于所选数据源的指标自动编排大屏；成功后跳转到新大屏的编辑器。 */
async function submitAiBuild(): Promise<void> {
  if (pickedDs.value == null) {
    ElMessage.warning('请选择数据源')
    return
  }
  await runAiBuild(async () => {
    try {
      const r = await dashboardApi.aiBuild(pickedDs.value as number, aiIntent.value.trim() || undefined)
      if (r.submitted) {
        ElMessage.success('已提交后台，AI 大屏正在生成，稍后到驾驶舱列表查看')
      } else {
        ElMessage.success(
          `已生成 AI 大屏（${r.widgets} 个组件，方案：${r.source === 'llm' ? 'AI 布局增强' : '确定性基线'}）` +
            (r.summary ? `：${r.summary}` : '')
        )
        if (r.dashboardId) void router.push(`/dashboards/${r.dashboardId}/edit`)
      }
      aiBuildVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

</script>

<template>
  <div class="dash-list-view">
    <div class="dash-layout">
      <!-- 左侧：多层级分组目录树 -->
      <aside class="group-sidebar">
        <div class="gs-head">
          <span class="gs-title">分组</span>
          <el-button
            v-if="can('dashboard:item:write')"
            size="small"
            text
            class="gs-add"
            @click="openCreateGroup(null)"
          >
            <el-icon><Plus /></el-icon> 新建
          </el-button>
        </div>
        <el-tree
          class="gs-tree"
          :data="treeData"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          :expand-on-click-node="false"
          highlight-current
          default-expand-all
          :current-node-key="selectedKey"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="gs-node">
              <span class="gs-node-label truncate">{{ data.name }}</span>
              <span v-if="!data.isSpecial && can('dashboard:item:write')" class="gs-node-acts">
                <el-icon title="新建子分组" @click.stop="openCreateGroup(data.id as number)"><Plus /></el-icon>
                <el-icon title="重命名 / 移动" @click.stop="openEditGroup(data)"><Edit /></el-icon>
                <el-icon
                  v-if="can('dashboard:item:delete')"
                  title="删除（仅空分组）"
                  @click.stop="removeGroup(data)"
                ><Delete /></el-icon>
              </span>
            </span>
          </template>
        </el-tree>
      </aside>

      <!-- 右侧：列表主区 -->
      <section class="dash-main">
        <div class="page-head">
          <div>
            <h2 class="ph-title">驾驶舱</h2>
            <p class="ph-desc">
              把指标编排成可视化驾驶舱：拖拽自由布局、一键自动设计、内置模板，并可发布为全屏数据大屏。
            </p>
          </div>
          <div class="head-actions">
            <el-button @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
            <el-button v-if="can('dashboard:item:write')" type="primary" class="add-btn" @click="openCreate">
              <el-icon><Plus /></el-icon> 新建驾驶舱
            </el-button>
            <el-button
              v-if="can('dashboard:cockpit:ai') && config.cockpitAiDashboardEnabled"
              type="warning"
              class="add-btn"
              @click="openAiBuild"
            >
              <el-icon><MagicStick /></el-icon> 生成 AI 大屏
            </el-button>
          </div>
        </div>

        <div v-loading="loading" class="dash-grid">
          <div
            v-for="d in visibleDashboards"
            :key="d.id"
            class="dash-card card-hover"
            :class="{ 'is-stale': dashboardFreshness(d).stale }"
          >
            <div class="dc-cover" :style="coverStyle(d.theme)">
              <el-icon :size="34"><DataBoard /></el-icon>
              <div class="dc-cover-meta">
                <span class="dc-widgets">{{ (d.layout || []).length }} 个组件</span>
                <el-tag v-if="d.templateCode" size="small" effect="dark" round>模板</el-tag>
              </div>
            </div>
            <el-tooltip
              v-if="dashboardFreshness(d).text"
              :content="dashboardFreshness(d).tip"
              placement="top"
            >
              <div class="dc-fresh" :class="{ 'is-warn': dashboardFreshness(d).stale }">
                <el-icon><WarningFilled /></el-icon> {{ dashboardFreshness(d).text }}
              </div>
            </el-tooltip>
            <div class="dc-body">
              <div class="dc-name-row">
                <span class="dc-name truncate">{{ d.name }}</span>
                <div class="dc-name-tags">
                  <el-tag v-if="d.groupId && groupNameMap[d.groupId]" size="small" effect="plain" round>
                    {{ groupNameMap[d.groupId] }}
                  </el-tag>
                  <el-tag v-if="d.isScreen === 1" size="small" type="warning" effect="plain" round>大屏</el-tag>
                </div>
              </div>
              <p class="dc-desc">{{ d.description || '暂无描述' }}</p>
              <div class="dc-meta muted small">
                <span>{{ themeLabel(d.theme) }} · {{ d.refreshSeconds || 30 }}s 刷新</span>
                <span>{{ formatDateTime(d.updatedAt) }}</span>
              </div>
              <div class="dc-actions">
                <el-button
                  v-if="can('dashboard:item:write')"
                  size="small"
                  type="primary"
                  text
                  @click="router.push(`/dashboards/${d.id}/edit`)"
                >
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button size="small" text @click="openScreen(d)">
                  <el-icon><FullScreen /></el-icon> 大屏
                </el-button>
                <el-button v-if="can('lineage:metric:read')" size="small" text @click="openLineage(d)">
                  <el-icon><Connection /></el-icon> 血缘
                </el-button>
                <el-button v-if="can('dashboard:item:share')" size="small" text @click="openShare(d)">
                  <el-icon><Share /></el-icon> 分享
                </el-button>
                <el-dropdown
                  v-if="can('dashboard:item:write')"
                  trigger="click"
                  @command="(gid: number | null) => moveDashboard(d, gid)"
                >
                  <el-button size="small" text><el-icon><Folder /></el-icon> 分组</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item :command="null">未分组</el-dropdown-item>
                      <el-dropdown-item v-for="g in flatGroupOptions" :key="g.id" :command="g.id">
                        {{ g.indent }}{{ g.name }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button
                  v-if="can('dashboard:item:delete')"
                  size="small"
                  text
                  type="danger"
                  class="ops-danger"
                  title="删除驾驶舱"
                  @click="removeDashboard(d)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>

          <el-empty
            v-if="!loading && visibleDashboards.length === 0"
            class="empty-block"
            :description="selectedKey === 'ungrouped'
              ? '未分组的驾驶舱会显示在这里'
              : '该分组下还没有驾驶舱，点击「新建驾驶舱」开始'"
          />
        </div>
      </section>
    </div>

    <!-- 新建驾驶舱对话框 -->
    <el-dialog v-model="createVisible" title="新建驾驶舱" width="720px" align-center top="6vh">
      <el-radio-group v-model="createMode" class="mode-switch" @change="(v: string) => switchMode(v as CreateMode)">
        <el-radio-button value="blank"><el-icon><Document /></el-icon> 空白</el-radio-button>
        <el-radio-button value="auto"><el-icon><MagicStick /></el-icon> 一键自动布局</el-radio-button>
        <el-radio-button value="template"><el-icon><Grid /></el-icon> 从模板</el-radio-button>
      </el-radio-group>

      <div class="create-body">
        <!-- 模板画廊 -->
        <div v-if="createMode === 'template'" class="tpl-section">
          <p class="sec-hint muted">选择一套内置模板骨架，之后可把指标按顺序绑定到槽位。</p>
          <TemplateGallery @select="onPickTemplate" />
          <el-alert
            v-if="pickedTemplate"
            class="picked-alert"
            type="success"
            :closable="false"
            show-icon
            :title="`已选模板：${pickedTemplate.name}（${(pickedTemplate.layout || []).length} 个槽位）`"
          />
        </div>

        <!-- 指标选择（自动 / 模板） -->
        <div v-if="createMode !== 'blank'" class="metric-section">
          <p class="sec-hint muted">
            {{
              createMode === 'auto'
                ? '选择要编排的指标，系统按图表类型自动生成 12 列均衡网格。'
                : '按顺序选择指标绑定到模板槽位（不足留空、超出忽略）。'
            }}
          </p>
          <el-select
            v-model="form.metricIds"
            multiple
            filterable
            placeholder="选择指标（可多选，顺序即绑定顺序）"
            style="width: 100%"
          >
            <el-option v-for="m in metrics" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <p v-if="metrics.length === 0" class="no-metric muted">
            还没有指标，请先在「对话问数」把结果存为指标。
          </p>
        </div>

        <!-- 基本信息 -->
        <el-form label-width="76px" class="base-form">
          <el-form-item label="名称">
            <el-input v-model="form.name" placeholder="例如：经营概览驾驶舱" maxlength="64" clearable />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" placeholder="可选" maxlength="200" clearable />
          </el-form-item>
          <el-form-item label="主题">
            <el-select v-model="form.theme" style="width: 200px">
              <el-option v-for="o in themeOpts" :key="o.key" :label="o.label" :value="o.key">
                <span class="theme-opt">
                  <span class="theme-swatch">
                    <i v-for="(c, ci) in o.swatch" :key="ci" :style="{ background: c }" />
                  </span>
                  {{ o.label }}
                </span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="刷新(秒)">
            <el-input-number v-model="form.refreshSeconds" :min="5" :max="600" :step="5" controls-position="right" />
            <el-checkbox v-model="form.isScreen" :true-value="1" :false-value="0" style="margin-left: 16px">
              标记为数据大屏
            </el-checkbox>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建并编辑</el-button>
      </template>
    </el-dialog>

    <!-- 分组新建 / 重命名·移动对话框 -->
    <el-dialog
      v-model="groupDialogVisible"
      :title="groupFormMode === 'create' ? '新建分组' : '重命名 / 移动分组'"
      width="460px"
      align-center
    >
      <el-form label-width="84px" class="group-form">
        <el-form-item label="分组名称">
          <el-input v-model="groupForm.name" placeholder="例如：经营分析" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="上级分组">
          <el-select v-model="groupForm.parentId" clearable placeholder="根级（不挂在任一分组下）" style="width: 100%">
            <el-option v-for="g in groupParentOptions" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
        <p class="muted small gf-hint">空=根级；选择上级即把本分组（含其下整棵子树）挂到该分组之下。</p>
      </el-form>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="groupSubmitting" @click="submitGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 大屏分享对话框（问题2） -->
    <DashboardShareDialog v-model="shareVisible" :dashboard-id="shareTarget.id" :dashboard-name="shareTarget.name" />

    <!-- 驾驶舱 AI 自动化大屏（Skill）：选数据源 + 风格指令 -->
    <el-dialog v-model="aiBuildVisible" title="生成 AI 大屏" width="520px" align-center top="12vh">
      <p class="muted small" style="margin: 0 0 12px">
        选择数据源后，系统会基于该数据源已分析的指标自动编排一张可视化大屏。
        若尚未生成指标，请先到该数据源的「语义管理」运行「指标自动分析」。
      </p>
      <el-form label-width="84px" class="aibuild-form">
        <el-form-item label="数据源">
          <el-select v-model="pickedDs" filterable placeholder="选择数据源" style="width: 100%">
            <el-option v-for="d in dsOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <p v-if="dsOptions.length === 0" class="muted small" style="margin: 6px 0 0">
            暂无数据源，请先在「数据源」中添加。
          </p>
        </el-form-item>
        <el-form-item label="风格指令">
          <el-input
            v-model="aiIntent"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="可选：给 AI 大屏一些风格要求（如「科技感深色、给领导看的经营看板」），留空则按默认"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="aiBuildVisible = false">取消</el-button>
        <el-button type="primary" :loading="building" @click="submitAiBuild">生成</el-button>
      </template>
    </el-dialog>

    <!-- 看板血缘（P1）：该看板用了哪些指标 → 这些指标来自哪些表/字段 -->
    <el-dialog v-model="lineageVisible" title="看板血缘 · 数据来源" width="620px">
      <div v-loading="lineageLoading">
        <p v-if="lineageData" class="muted small">
          「{{ lineageData.dashboardName }}」共引用 <b>{{ lineageData.metricCount ?? 0 }}</b> 个指标，
          追溯到 {{ lineageData.sources?.length ?? 0 }} 张来源表。
        </p>

        <el-empty
          v-if="lineageData && !lineageData.metrics?.length"
          description="该看板尚未绑定指标（或布局中未识别到 metricId）"
          :image-size="64"
        />

        <template v-else-if="lineageData">
          <h4 class="lg-sub">引用指标（{{ lineageData.metrics?.length ?? 0 }}）</h4>
          <el-table :data="lineageData.metrics || []" size="small" border max-height="180">
            <el-table-column prop="name" label="指标" min-width="180" show-overflow-tooltip />
            <el-table-column prop="id" label="ID" width="72" align="center" />
          </el-table>

          <h4 class="lg-sub">来源表与字段</h4>
          <div v-for="(s, i) in lineageData.sources || []" :key="s.table ?? i" class="lg-source">
            <div class="lg-table mono">{{ s.table }}</div>
            <div class="lg-fields">
              <el-tag
                v-for="f in s.fields || []"
                :key="f.fieldId"
                size="small"
                effect="plain"
                :type="f.sensitive === 1 ? 'danger' : 'info'"
                round
              >
                {{ f.label }}<span v-if="f.role && f.role !== 'AUTO'" class="lg-role">·{{ f.role }}</span>
              </el-tag>
            </div>
          </div>
          <p class="muted small lg-tail">
            红色标签为标记敏感的引用字段；修改这些字段的别名 / 口径 / 列角色可能影响本看板。
          </p>
        </template>
      </div>
      <template #footer>
        <el-button size="small" @click="lineageVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dash-list-view {
  display: flex;
  height: 100%;
  overflow: hidden;
  padding: 22px 26px 32px;
}
.dash-layout {
  flex: 1;
  display: flex;
  gap: 16px;
  min-height: 0;
}
/* 左侧分组目录树 */
.group-sidebar {
  width: 224px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 12px 10px;
  overflow-y: auto;
}
.gs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 10px;
}
.gs-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-secondary);
}
.gs-add {
  padding: 2px 6px;
}
.gs-tree {
  flex: 1;
  border: none;
  background: transparent;
}
.gs-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 6px;
}
.gs-node-label {
  flex: 1;
  min-width: 0;
}
.gs-node-acts {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
}
.gs-node:hover .gs-node-acts {
  opacity: 1;
}
.gs-node-acts .el-icon {
  cursor: pointer;
  font-size: 14px;
  color: var(--text-secondary);
  padding: 2px;
  border-radius: 3px;
}
.gs-node-acts .el-icon:hover {
  background: var(--fill-light, #f5f7fa);
  color: var(--brand, #409eff);
}
/* 右侧主区 */
.dash-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
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
  max-width: 760px;
}
.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.add-btn {
  background: var(--grad-btn);
  border: none;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}
.dash-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  /* 行高按内容自适应、行起点对齐顶部：避免卡片被网格行压缩（同「指标库告警条被压扁」的 flex 压缩家族缺陷）。
     卡片行数超出可视高度时由本容器自身滚动（overflow-y:auto），不再回压卡片高度。 */
  grid-auto-rows: min-content;
  align-content: start;
  gap: 16px;
  min-height: 200px;
  flex: 1;
  overflow-y: auto;
  padding: 4px 2px 8px;
}
.dash-card {
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-light);
  background: var(--bg-card);
  display: flex;
  flex-direction: column;
  /* 刻意不设 overflow:hidden——卡片内部一旦因父级压缩而溢出，宁可可见也不要被静默裁掉操作按钮行。
     圆角改由封面自身处理（见 .dc-cover）。 */
}
.dc-cover {
  height: 92px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
}
.dc-cover-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dc-widgets {
  font-size: 12px;
  font-weight: 600;
}
.dc-body {
  padding: 12px 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}
.dc-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dc-name-tags {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.dc-name {
  font-size: 15.5px;
  font-weight: 700;
  color: var(--text-primary);
}
.dc-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.5;
  height: 37px;
  overflow: hidden;
}
.dc-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.small {
  font-size: 11.5px;
}
.dc-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  border-top: 1px solid var(--border-light);
  padding-top: 8px;
  margin-top: auto;
  flex-wrap: wrap;
}
.empty-block {
  grid-column: 1 / -1;
}
/* ---------- 数据新鲜度（P2）：过期看板整卡标黄 ---------- */
.dash-card.is-stale {
  border-color: rgba(230, 162, 60, 0.6);
}
.dc-fresh {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  font-size: 11.5px;
  color: var(--text-3, #909399);
  background: var(--fill-light, #f5f7fa);
  border-bottom: 1px solid var(--border-light, #ebeef5);
}
.dc-fresh.is-warn {
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.12);
}
.mode-switch {
  margin-bottom: 16px;
}
.create-body {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}
.sec-hint {
  margin: 0 0 10px;
  font-size: 12.5px;
}
.picked-alert {
  margin-top: 12px;
}
.metric-section {
  margin-bottom: 16px;
}
.no-metric {
  margin: 8px 0 0;
  font-size: 12px;
}
.base-form {
  border-top: 1px dashed var(--border);
  padding-top: 14px;
}
.group-form {
  padding-top: 4px;
}
.gf-hint {
  margin: -4px 0 0;
}
.theme-opt {
  display: flex;
  align-items: center;
  gap: 8px;
}
.theme-swatch {
  display: inline-flex;
  gap: 2px;
}
.theme-swatch i {
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

/* 看板血缘对话框 */
.lg-sub {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 700;
}
.lg-source {
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border-light);
  border-radius: 6px;
}
.lg-table {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 6px;
}
.lg-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.lg-role {
  opacity: 0.7;
  margin-left: 2px;
}
.lg-tail {
  margin: 10px 0 0;
}
</style>
