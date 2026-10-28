/**
 * 数据源管理页共享态（T7.3 自 DataSourceView 逐字搬移）：
 * store 装载 + 分类 tab（文件 → 关系型 → 数仓）派生与自动切换。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useDatasourceStore } from '@/stores/datasource'
import { CATEGORY_LABEL, CATEGORY_ORDER, categoryOf, type DsCategory } from './dsUtils'

export function useDsCore() {
  const dsStore = useDatasourceStore()

  const activeCategory = ref<DsCategory>('file')

  function countOf(key: DsCategory): number {
    return dsStore.list.filter((d) => categoryOf(d.type) === key).length
  }

  /** tab 标签（带各自数量，便于一眼看出数据分布）。 */
  const categoryTabs = computed(() =>
    CATEGORY_ORDER.map((k) => ({ key: k, label: CATEGORY_LABEL[k], count: countOf(k) }))
  )

  /** 当前分类下的卡片。 */
  const filteredList = computed(() =>
    dsStore.list.filter((d) => categoryOf(d.type) === activeCategory.value)
  )

  /**
   * 默认停「文件」，但首次加载后若该分类没有数据、别处却有，自动切到第一个非空分类。
   * 理由：默认值 + 空 tab 组合起来会让用户第一眼以为「没数据 / 页面坏了」，而 tab 顺序并未因此改变。
   */
  watch(
    () => dsStore.list.length,
    () => {
      if (filteredList.value.length === 0) {
        const first = CATEGORY_ORDER.find((k) => countOf(k) > 0)
        if (first) activeCategory.value = first
      }
    },
    { once: true }
  )

  onMounted(() => {
    void dsStore.load().catch(() => undefined)
  })

  return { dsStore, activeCategory, categoryTabs, filteredList }
}

export type DsCore = ReturnType<typeof useDsCore>
