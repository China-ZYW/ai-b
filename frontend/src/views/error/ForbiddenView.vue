<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 无权访问页（P4，见《角色权限体系与组织协同设计》7.7）。
 * 路由守卫在角色/权限点门禁不通过时重定向到此，比裸 403 更有用：告知来源页并给出返回入口。
 */
const route = useRoute()
const router = useRouter()

const from = computed(() => (route.query.from as string) || '')

function goBack(): void {
  if (window.history.length > 1) router.back()
  else router.push('/app/chat')
}
</script>

<template>
  <div class="fb">
    <div class="fb-code grad-text">403</div>
    <p class="fb-text">当前角色无权访问该页面</p>
    <p v-if="from" class="fb-from">来源：<code>{{ from }}</code></p>
    <p class="fb-tip">如需访问，请联系组织管理员为你分配相应角色或权限点。</p>
    <div class="fb-actions">
      <el-button type="primary" size="large" @click="goBack">
        <el-icon><Back /></el-icon> 返回上一页
      </el-button>
      <el-button size="large" @click="router.push('/app/chat')">
        <el-icon><HomeFilled /></el-icon> 回到问数
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.fb {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: var(--bg-page);
  padding: 0 24px;
  text-align: center;
}
.fb-code {
  font-size: 96px;
  font-weight: 900;
  line-height: 1;
}
.fb-text {
  font-size: 16px;
  color: var(--text-primary);
  margin: 0;
}
.fb-from {
  color: var(--text-secondary);
  margin: 0;
  font-size: 13px;
}
.fb-from code {
  background: var(--ikb-ghost);
  padding: 2px 8px;
  border-radius: 6px;
}
.fb-tip {
  color: var(--text-secondary);
  margin: 0 0 8px;
  font-size: 13px;
}
.fb-actions {
  display: flex;
  gap: 12px;
}
</style>
