/**
 * 数据源行操作与表结构抽屉（T7.3 自 DataSourceView 逐字搬移）：
 * 测试/同步的行级分桶 loading、删除确认、表结构抽屉、结构对账跳转。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDatasourceStore } from '@/stores/datasource'
import type { DataSourceVO, TableMeta } from '@/types'
import { useRowBusy } from '@/utils/actionGuard'

export function useDsActions() {
  const dsStore = useDatasourceStore()
  const router = useRouter()

  /* ---------------- 行操作 ---------------- */
  // 按行分桶：并发点两行的「测试连接」不会互相顶掉 loading（原为单个 ref<number|null>）
  const testBusy = useRowBusy<number>()

  async function testSaved(d: DataSourceVO): Promise<void> {
    await testBusy.run(d.id, async () => {
      try {
        const r = await dsStore.testById(d.id)
        if (r.success) ElMessage.success(`连接成功（${r.elapsedMs} ms）`)
        else ElMessage.error(r.message || '连接失败')
      } catch {
        // 拦截器已提示
      }
    })
  }

  /**
   * 立即同步表结构（后端失效 schema 缓存 → 重扫 → 回写 last_schema_sync_at）。
   * 表新增/改名/删列后点它，问数侧立刻看到最新结构，不必等 30 分钟定时重扫。
   */
  const syncBusy = useRowBusy<number>()

  async function syncSchema(d: DataSourceVO): Promise<void> {
    await syncBusy.run(d.id, async () => {
      try {
        const tables = await dsStore.sync(d.id)
        ElMessage.success(`已同步 ${tables.length} 张表`) // 拦截器出错则不会走到这
      } catch {
        // 拦截器已提示
      }
    })
  }

  async function removeDs(d: DataSourceVO): Promise<void> {
    try {
      await ElMessageBox.confirm(`确定删除数据源「${d.name}」吗？此操作不可撤销。`, '删除确认', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    try {
      await dsStore.remove(d.id)
      ElMessage.success('已删除')
    } catch {
      // 拦截器已提示
    }
  }

  /* ---------------- 表结构抽屉 ---------------- */
  const schemaVisible = ref(false)
  const schemaLoading = ref(false)
  const schemaData = ref<TableMeta[]>([])
  const schemaSource = ref<DataSourceVO | null>(null)

  async function openSchema(d: DataSourceVO): Promise<void> {
    schemaSource.value = d
    schemaVisible.value = true
    schemaLoading.value = true
    schemaData.value = []
    try {
      schemaData.value = await dsStore.schema(d.id)
    } catch {
      // 拦截器已提示
    } finally {
      schemaLoading.value = false
    }
  }

  /**
   * 结构对账入口：把该数据源带进对账页（?datasourceId=），一次性列出其下游元数据与物理结构的漂移。
   * 权限点复用 semantic:meta:read（与语义层治理同域）。
   */
  function goDrift(d: DataSourceVO): void {
    router.push({ path: '/app/schema-drift', query: { datasourceId: String(d.id) } })
  }

  return {
    testBusy,
    testSaved,
    syncBusy,
    syncSchema,
    removeDs,
    schemaVisible,
    schemaLoading,
    schemaData,
    schemaSource,
    openSchema,
    goDrift
  }
}

export type DsActions = ReturnType<typeof useDsActions>
