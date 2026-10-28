/**
 * 指标 AI 自动化（Skill，T7.3 自 MetricView 逐字抽离）：
 * 指标自动分析 → 建指标字典；生成 AI 大屏 → 编排大屏（均提交即返回，实际在后台跑）。
 * 指标库跨数据源、无单源上下文，对话框内先拉数据源列表供用户选择。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import { datasourceApi } from '@/api/datasource'
import { semanticApi } from '@/api/semantic'
import type { DataSourceVO } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'
import type { MetricCore } from './useMetricCore'

export function useMetricAi(core: MetricCore) {
  const aiVisible = ref(false)
  const aiMode = ref<'analysis' | 'build'>('analysis')
  const aiDsOptions = ref<DataSourceVO[]>([])
  const aiPickedDs = ref<number | null>(null)
  const aiIntent = ref('')
  const { busy: aiSubmitting, run: runAi } = useActionGuard()

  /** 拉数据源列表并打开对话框（analysis / build 两模式共用）。 */
  async function openAi(mode: 'analysis' | 'build'): Promise<void> {
    aiMode.value = mode
    aiIntent.value = ''
    aiPickedDs.value = null
    try {
      aiDsOptions.value = await datasourceApi.list()
    } catch {
      aiDsOptions.value = []
    }
    aiVisible.value = true
  }

  /** 打开「指标自动分析」对话框。 */
  async function openAiAnalysis(): Promise<void> {
    await openAi('analysis')
  }

  /** 打开「生成 AI 大屏」对话框。 */
  async function openAiBuild(): Promise<void> {
    await openAi('build')
  }

  /** 提交后台异步生成：指标自动分析 → 建指标字典；生成 AI 大屏 → 编排大屏（均提交即返回，实际在后台跑）。 */
  async function submitAi(): Promise<void> {
    if (aiPickedDs.value == null) {
      ElMessage.warning('请选择数据源')
      return
    }
    await runAi(async () => {
      try {
        if (aiMode.value === 'analysis') {
          const r = await semanticApi.autoAnalysis(aiPickedDs.value as number)
          ElMessage.success(r.submitted ? '已提交后台，指标正在自动分析中，稍后刷新指标库查看' : `已自动分析：${r.metrics} 个指标`)
        } else {
          const r = await dashboardApi.aiBuild(aiPickedDs.value as number, aiIntent.value.trim() || undefined)
          ElMessage.success(r.submitted ? '已提交后台，AI 大屏正在生成，稍后到驾驶舱列表查看' : 'AI 大屏已生成')
        }
        aiVisible.value = false
        await core.load()
      } catch {
        // 拦截器已提示
      }
    })
  }

  return { aiVisible, aiMode, aiDsOptions, aiPickedDs, aiIntent, aiSubmitting, openAiAnalysis, openAiBuild, submitAi }
}
