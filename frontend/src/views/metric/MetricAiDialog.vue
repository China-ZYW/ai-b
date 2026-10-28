<script setup lang="ts">
/**
 * 指标 AI 自动化对话框（T7.3 自 MetricView 拆出）：
 * 指标自动分析（建指标字典）/ 生成 AI 大屏（编排大屏），均提交即返回、后台异步跑。
 * 状态与提交走 useMetricCtx。
 */
import { useMetricCtx } from './useMetric'

const ctx = useMetricCtx()
</script>

<template>
  <el-dialog
    v-model="ctx.aiVisible"
    :title="ctx.aiMode === 'analysis' ? '指标自动分析' : '生成 AI 大屏'"
    width="520px"
    align-center
    top="12vh"
  >
    <p class="muted small" style="margin: 0 0 12px">
      <template v-if="ctx.aiMode === 'analysis'">选择数据源后，系统会扫描其库表结构、自动识别指标/维度并生成指标字典（打 AI 标识）。</template>
      <template v-else>选择数据源后，系统会基于该数据源已分析的指标自动编排一张可视化大屏；若尚未生成指标，请先运行「指标自动分析」。</template>
    </p>
    <el-form label-width="84px" class="aibuild-form">
      <el-form-item label="数据源">
        <el-select v-model="ctx.aiPickedDs" filterable placeholder="选择数据源" style="width: 100%">
          <el-option v-for="d in ctx.aiDsOptions" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <p v-if="ctx.aiDsOptions.length === 0" class="muted small" style="margin: 6px 0 0">暂无数据源，请先在「数据源」中添加。</p>
      </el-form-item>
      <el-form-item v-if="ctx.aiMode === 'build'" label="风格指令">
        <el-input
          v-model="ctx.aiIntent"
          type="textarea"
          :rows="3"
          maxlength="200"
          placeholder="可选：给 AI 大屏一些风格要求（如「科技感深色、给领导看的经营看板」），留空则按默认"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="ctx.aiVisible = false">取消</el-button>
      <el-button type="primary" :loading="ctx.aiSubmitting" @click="ctx.submitAi">生成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 「muted small」用了 small 工具类：原视图 scoped 定义，拆分后由本组件自持 */
.small {
  font-size: 11.5px;
}
</style>
