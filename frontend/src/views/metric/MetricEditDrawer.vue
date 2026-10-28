<script setup lang="ts">
/**
 * 指标编辑抽屉（T7.3 自 MetricView 拆出）：名称/描述/别名/canonical 口径/
 * 归因默认维度与基准/新鲜度 SLA/原始 SQL/列展示名/图表标注 + 数据血缘卡片。
 * 状态与提交逻辑全部走 useMetricCtx；el-form 实例经回调式 :ref 写回 ctx.editFormRef
 * （字符串 ref 绑不到注入对象成员，见 T7.1 坑解法）。
 */
import type { FormInstance } from 'element-plus'
import ChartStyleForm from '@/components/chart/ChartStyleForm.vue'
import { useMetricCtx } from './useMetric'

const ctx = useMetricCtx()
</script>

<template>
  <el-drawer v-model="ctx.editVisible" title="编辑指标" size="480px">
    <el-form
      :ref="(el: Element | null) => (ctx.editFormRef = el as unknown as FormInstance | undefined)"
      :model="ctx.editForm"
      :rules="ctx.editRules"
      label-width="78px"
    >
      <el-form-item label="名称" prop="name">
        <el-input v-model="ctx.editForm.name" maxlength="64" show-word-limit clearable />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="ctx.editForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="指标别名">
        <el-input v-model="ctx.editForm.alias" maxlength="64" show-word-limit clearable placeholder="图表标题/大屏组件展示名，留空则用指标名称" />
      </el-form-item>
      <el-form-item label="canonical 口径">
        <el-input
          v-model="ctx.editForm.definition"
          placeholder="如 SUM(amount)；留空=未沉淀，归因将不可用"
          clearable
        />
        <div class="muted" style="font-size: 12px; line-height: 1.6; margin-top: 4px">
          趋势 / 量价因子 / 维度归因共用此口径（单一事实源）。归因要求<b>可加度量</b>——含除法的比率类
          （毛利率、客单价）各维度增量相加无业务含义，会被后端拒绝；量价拆解另需语义层把量列标为「度量」。
        </div>
      </el-form-item>
      <el-form-item label="归因默认维度">
        <el-select
          v-model="ctx.editForm.attributionDimension"
          size="small"
          clearable
          filterable
          allow-create
          default-first-option
          placeholder="保存归因默认维度，下次打开自动执行"
          style="width: 100%"
        >
          <el-option v-for="d in ctx.editDimensions" :key="d.column" :label="d.label" :value="d.column" />
        </el-select>
      </el-form-item>
      <el-form-item label="归因默认基准">
        <el-select v-model="ctx.editForm.baselineType" size="small" placeholder="对比基准" style="width: 100%">
          <el-option v-for="b in ctx.baselineOptions" :key="b.value" :label="b.label" :value="b.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="新鲜度 SLA">
        <el-input-number
          v-model="ctx.editForm.freshnessHours"
          :min="0"
          :max="720"
          :step="4"
          size="small"
          controls-position="right"
          style="width: 100%"
        />
        <div class="muted" style="font-size: 12px; line-height: 1.6; margin-top: 4px">
          该指标数据超过此小时数未刷新即标黄提醒；留空或 0 = 沿用全局阈值（默认 24 小时）。
          T+1 离线表可设 36、近实时表可设 4。
        </div>
      </el-form-item>
      <el-form-item label="数据源">
        <span class="muted">{{ ctx.dsName(ctx.editing?.datasourceId) }}</span>
      </el-form-item>
      <el-form-item label="原始 SQL">
        <div class="sql-box">
          <pre class="sql-pre mono">{{ ctx.editing?.sql || '（无原始 SQL）' }}</pre>
          <el-button
            v-if="ctx.editing?.sql"
            size="small"
            text
            type="primary"
            class="sql-copy"
            @click="ctx.copySql"
          >
            <el-icon><DocumentCopy /></el-icon> 复制
          </el-button>
        </div>
        <div class="muted" style="font-size: 12px; line-height: 1.6; margin-top: 4px">
          该指标取数所用的原始 SQL（自动分析 / 问数结果保存时一并沉淀，只读展示）。
        </div>
      </el-form-item>
      <el-form-item label="列展示名">
        <div v-loading="ctx.editColsLoading" class="label-list">
          <template v-if="ctx.editLabelRows.length">
            <div v-for="r in ctx.editLabelRows" :key="r.column" class="label-row">
              <span class="label-col mono" :title="`物理列名：${r.column}`">{{ r.column }}</span>
              <el-icon class="label-arrow"><Right /></el-icon>
              <el-input
                v-model="ctx.editLabels[r.column]"
                size="small"
                maxlength="64"
                :placeholder="r.fallback"
                clearable
              />
            </div>
            <span class="label-tip">
              汇总/派生列（如 SUM(amount) AS total_amount）在语义层没有列行，可在此按指标自定义叫法；
              优先级高于语义层别名，置空则回落。仅改表头/图例文案，不影响图表字段匹配与 SQL。
            </span>
          </template>
          <el-button v-else size="small" text type="primary" :loading="ctx.editColsLoading" @click="ctx.reloadEditCols">
            <el-icon><Refresh /></el-icon> 读取结果列
          </el-button>
        </div>
      </el-form-item>
      <el-form-item v-if="ctx.editing?.chartSpec && ctx.editing.chartSpec.type !== 'TABLE'" label="图表标注">
        <div class="style-box">
          <ChartStyleForm
            :model-value="ctx.editStyle"
            :chart-type="ctx.editing.chartSpec.type"
            @update:model-value="ctx.onEditStyle"
          />
          <span class="label-tip">
            随指标保存（chart_spec.style），指标预览、分享快照与引用该指标的大屏 widget 均按此渲染；
            留空则跟随租户级默认。只改展示，不影响 SQL 与数据。
          </span>
        </div>
      </el-form-item>
    </el-form>

    <!-- 数据血缘卡片（需求 §3.3）：数据来源 / 口径 / 影响范围 -->
    <div v-if="ctx.can('lineage:metric:read')" class="lineage-box">
      <el-divider content-position="left">
        <span class="lg-title">数据血缘</span>
      </el-divider>
      <div v-loading="ctx.lineageLoading">
        <template v-if="ctx.lineage">
          <p class="lg-row">
            <span class="lg-key">指标口径</span>
            <span class="mono">{{ ctx.lineage.definition || '（未沉淀口径，血缘为空）' }}</span>
          </p>
          <div v-if="ctx.lineage.sources?.length" class="lg-sources">
            <div v-for="(s, si) in ctx.lineage.sources" :key="si" class="lg-source">
              <div class="lg-table">
                <el-icon><Coin /></el-icon>
                <span class="mono">{{ s.table }}</span>
              </div>
              <div class="lg-fields">
                <el-tag
                  v-for="f in s.fields ?? []"
                  :key="f.fieldId"
                  size="small"
                  :type="f.sensitive === 1 ? 'danger' : 'info'"
                  effect="plain"
                >
                  {{ f.label }}
                </el-tag>
              </div>
            </div>
          </div>
          <p v-else class="muted lg-empty">暂无来源字段：请先在指标库沉淀口径表达式。</p>
          <p class="lg-row">
            <span class="lg-key">影响范围</span>
            <span v-if="ctx.lineage.dashboardCount">
              {{ ctx.lineage.dashboardCount }} 个看板引用
              <span class="muted">
                （{{ (ctx.lineage.referencedBy ?? []).map((d) => d.name).join('、') }}）
              </span>
            </span>
            <span v-else class="muted">暂无看板引用</span>
          </p>
        </template>
        <p v-else-if="!ctx.lineageLoading" class="muted lg-empty">打开抽屉即自动解析血缘。</p>
      </div>
    </div>

    <template #footer>
      <el-button @click="ctx.editVisible = false">取消</el-button>
      <el-button type="primary" :loading="ctx.submitting" @click="ctx.submitEdit">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
/* ---------- 编辑抽屉：列展示名 ---------- */
.label-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 24px;
}
.label-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.label-col {
  width: 118px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label-arrow {
  flex-shrink: 0;
  color: var(--ikb-lighter);
}
.label-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.style-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* ---------- 编辑抽屉：原始 SQL 只读展示 ---------- */
.sql-box {
  width: 100%;
  position: relative;
  display: flex;
}
.sql-pre {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  background: var(--bg-code, #f6f8fa);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md, 6px);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}
.sql-copy {
  position: absolute;
  top: 6px;
  right: 6px;
  background: rgba(255, 255, 255, 0.85);
  padding: 2px 8px;
}
/* 数据血缘卡片 */
.lineage-box {
  margin-top: 4px;
}
.lg-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.lg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  margin: 4px 0;
  line-height: 1.7;
}
.lg-key {
  flex: none;
  width: 62px;
  color: var(--el-text-color-secondary, #909399);
}
.lg-sources {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 6px 0;
}
.lg-source {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 6px 8px;
}
.lg-table {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
}
.lg-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.lg-empty {
  font-size: 12px;
  margin: 4px 0;
}
</style>
