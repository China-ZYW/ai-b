/**
 * 指标库共享态（T7.3 自 MetricView 逐字抽离）：列表/分组数据加载、
 * 目录树派生、双维度筛选（分组 + 关键词）、新鲜度与数据源名称等视图级状态。
 * 其余协作 composable / 子组件均通过 useMetricCtx（provide/inject）消费这里的成员。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { metricApi, metricGroupApi } from '@/api/metric'
import { useDatasourceStore } from '@/stores/datasource'
import { useConfigStore } from '@/stores/config'
import type { DashboardGroupVO, MetricVO } from '@/types'
import { usePerm } from '@/composables/usePerm'
import {
  buildGroupTree,
  chartLabel,
  descendantGroupIds,
  flattenGroupOptions,
  freshnessText,
  type GroupTreeNode
} from './metricUtils'

export function useMetricCore() {
  const router = useRouter()
  const dsStore = useDatasourceStore()
  const config = useConfigStore()
  const { can } = usePerm()

  const loading = ref(false)
  const metrics = ref<MetricVO[]>([])
  /** 指标分组目录树（resource_group 的 METRIC 域；与驾驶舱共用表、分域隔离）。 */
  const groups = ref<DashboardGroupVO[]>([])
  const keyword = ref('')
  /** 左侧目录树当前选中节点：'all'=全部，'ungrouped'=未分组，其余为真实分组 id。 */
  const selectedKey = ref<string | number>('all')

  async function load(): Promise<void> {
    loading.value = true
    try {
      metrics.value = await metricApi.list()
    } catch {
      // 拦截器已提示
    } finally {
      loading.value = false
    }
    // 分组独立加载：老后端（无 /api/metric/group）或接口异常时退化为「全部 / 未分组」两种视图，
    // 不把整页拖成空白（与驾驶舱列表同样的兼容点）。
    try {
      groups.value = await metricGroupApi.list()
    } catch {
      groups.value = []
    }
  }

  /** onMounted 初始化：数据源列表按需加载 + 指标/分组加载。 */
  async function init(): Promise<void> {
    if (dsStore.list.length === 0) await dsStore.load()
    await load()
  }

  const treeData = computed<GroupTreeNode[]>(() => buildGroupTree(groups.value))

  /** 分组 id → 名称（卡片上展示所属分组）。 */
  const groupNameMap = computed<Record<number, string>>(() => {
    const m: Record<number, string> = {}
    groups.value.forEach((g) => (m[g.id] = g.name))
    return m
  })

  /** 按选中节点过滤指标（分组维度，先于关键词筛选）。 */
  const visibleMetrics = computed<MetricVO[]>(() => {
    if (selectedKey.value === 'all') return metrics.value
    if (selectedKey.value === 'ungrouped') return metrics.value.filter((m) => !m.groupId)
    const ids = new Set(descendantGroupIds(groups.value, selectedKey.value as number))
    return metrics.value.filter((m) => m.groupId != null && ids.has(m.groupId))
  })

  /** 移动指标下拉用的扁平分组（带缩进）。 */
  const flatGroupOptions = computed(() => flattenGroupOptions(treeData.value))

  /** 关键词 + 分组双维度筛选（分组在前，关键词在后）。 */
  const filtered = computed(() => {
    const k = keyword.value.trim().toLowerCase()
    if (!k) return visibleMetrics.value
    return visibleMetrics.value.filter(
      (m) => m.name.toLowerCase().includes(k) || (m.description || '').toLowerCase().includes(k)
    )
  })

  /** 空态文案：区分「分组为空」与「全库为空」，避免停在某个分组时误提示"还没有指标"。 */
  const emptyText = computed(() => {
    if (keyword.value) return '没有匹配的指标'
    if (selectedKey.value === 'ungrouped') return '未分组的指标会显示在这里'
    if (selectedKey.value === 'all') return '还没有指标，去「对话问数」把结果存为指标吧'
    return '该分组下还没有指标，可用卡片上的「分组」按钮把指标移入'
  })

  /** 已过期指标数（P2 新鲜度）：顶部给管理员一个"有多少指标数据不可信"的全局提示。 */
  const staleCount = computed(() => metrics.value.filter((m) => m.stale).length)

  function dsName(id?: number | null): string {
    if (id == null) return '—'
    return dsStore.list.find((d) => d.id === id)?.name || `数据源#${id}`
  }

  function onNodeClick(data: GroupTreeNode): void {
    selectedKey.value = data.id
  }

  return {
    router,
    dsStore,
    config,
    can,
    loading,
    metrics,
    groups,
    keyword,
    selectedKey,
    load,
    init,
    treeData,
    groupNameMap,
    visibleMetrics,
    flatGroupOptions,
    filtered,
    emptyText,
    staleCount,
    dsName,
    onNodeClick,
    // 纯函数转发给模板侧（避免子组件各自 import）
    chartLabel,
    freshnessText
  }
}

export type MetricCore = ReturnType<typeof useMetricCore>
