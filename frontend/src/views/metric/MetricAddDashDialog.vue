<script setup lang="ts">
/**
 * 加入驾驶舱对话框（T7.3 自 MetricView 拆出）：选择已有驾驶舱，
 * 把指标追加到其布局底部。状态与提交走 useMetricCtx。
 */
import { useMetricCtx } from './useMetric'

const ctx = useMetricCtx()
</script>

<template>
  <el-dialog v-model="ctx.addVisible" title="加入驾驶舱" width="460px" align-center>
    <div v-loading="ctx.addLoading" class="add-box">
      <p class="add-hint muted">选择一个已有驾驶舱，把「{{ ctx.addMetric?.name }}」追加到其布局底部；也可先新建驾驶舱。</p>
      <el-radio-group v-model="ctx.pickedDashId" class="dash-list">
        <el-radio
          v-for="d in ctx.dashboards"
          :key="d.id"
          :value="d.id"
          class="dash-radio"
          border
        >
          <span class="dash-name">{{ d.name }}</span>
          <span class="muted small">{{ (d.layout || []).length }} 个组件</span>
        </el-radio>
      </el-radio-group>
      <el-empty v-if="!ctx.addLoading && ctx.dashboards.length === 0" description="还没有驾驶舱" :image-size="70" />
    </div>
    <template #footer>
      <el-button @click="ctx.router.push('/dashboards')">去新建</el-button>
      <el-button @click="ctx.addVisible = false">取消</el-button>
      <el-button type="primary" :loading="ctx.adding" :disabled="ctx.pickedDashId == null" @click="ctx.confirmAdd">
        加入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.add-box {
  min-height: 120px;
}
.add-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
}
.dash-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.dash-radio {
  width: 100%;
  margin-right: 0;
  height: auto;
  padding: 10px 14px;
  display: flex;
  align-items: center;
}
.dash-radio :deep(.el-radio__label) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;
}
.dash-name {
  font-weight: 600;
}
/* 「N 个组件」用了 small 工具类：原视图 scoped 定义，拆分后由本组件自持 */
.small {
  font-size: 11.5px;
}
</style>
