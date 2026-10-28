import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { datasourceApi } from '@/api/datasource'
import type { DataSourceRequest, DataSourceVO, FileUploadResult, TableMeta, TestConnectionResult } from '@/types'

/**
 * 数据源 store：列表、当前选中源、表结构缓存。
 * 问数页顶栏的"数据源下拉"与各管理操作共用此状态。
 */
export const useDatasourceStore = defineStore('datasource', () => {
  const list = ref<DataSourceVO[]>([])
  const currentId = ref<number | null>(null)
  const loading = ref(false)
  /** 表结构缓存：datasourceId → TableMeta[]。 */
  const schemaCache = ref<Record<number, TableMeta[]>>({})

  const current = computed<DataSourceVO | null>(
    () => list.value.find((d) => d.id === currentId.value) ?? null
  )
  const isEmpty = computed(() => list.value.length === 0)

  async function load(keepCurrent = true): Promise<void> {
    loading.value = true
    try {
      list.value = await datasourceApi.list()
      // 默认选中第一个（或保持原选中若仍存在）
      const stillExists = list.value.some((d) => d.id === currentId.value)
      if (!keepCurrent || !stillExists) {
        currentId.value = list.value.length > 0 ? list.value[0].id : null
      }
    } finally {
      loading.value = false
    }
  }

  function select(id: number | null): void {
    currentId.value = id
  }

  async function create(payload: DataSourceRequest): Promise<DataSourceVO> {
    const vo = await datasourceApi.create(payload)
    await load(false)
    currentId.value = vo.id
    return vo
  }

  async function update(id: number, payload: DataSourceRequest): Promise<DataSourceVO> {
    const vo = await datasourceApi.update(id, payload)
    delete schemaCache.value[id] // 结构可能变化，失效缓存
    await load()
    return vo
  }

  async function remove(id: number): Promise<void> {
    await datasourceApi.remove(id)
    delete schemaCache.value[id]
    if (currentId.value === id) currentId.value = null
    await load(false)
  }

  async function test(payload: DataSourceRequest): Promise<TestConnectionResult> {
    return datasourceApi.test(payload)
  }

  async function testById(id: number): Promise<TestConnectionResult> {
    return datasourceApi.testById(id)
  }

  /** 本地上传文件（问题3）：落盘并返回解析预览，供表单回填后再保存入库。 */
  async function upload(file: File): Promise<FileUploadResult> {
    return datasourceApi.upload(file)
  }

  /**
   * 立即同步表结构：后端会失效 schema 缓存并重扫，故本地缓存一并清除、列表回写同步时间。
   * 返回的即最新 TableMeta[]，调用方可直接消费而不必再取一次。
   */
  async function sync(id: number): Promise<TableMeta[]> {
    const meta = await datasourceApi.sync(id)
    delete schemaCache.value[id] // 远端已重扫，本地旧缓存失效
    await load() // 回写 last_schema_sync_at
    return meta
  }

  /** 取表结构（带缓存）。 */
  async function schema(id: number, force = false): Promise<TableMeta[]> {
    if (!force && schemaCache.value[id]) return schemaCache.value[id]
    const meta = await datasourceApi.schema(id)
    schemaCache.value[id] = meta
    return meta
  }

  return {
    list,
    currentId,
    current,
    loading,
    isEmpty,
    schemaCache,
    load,
    select,
    create,
    update,
    remove,
    test,
    testById,
    upload,
    schema,
    sync
  }
})
