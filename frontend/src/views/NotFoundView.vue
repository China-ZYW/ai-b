<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="nf">
    <div class="nf-code grad-text">404</div>
    <p class="nf-text">页面走丢了，找不到你访问的内容</p>
    <el-button type="primary" size="large" @click="router.push('/app/chat')">
      <el-icon><HomeFilled /></el-icon> 返回问数
    </el-button>
  </div>
</template>

<style scoped>
.nf {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: var(--bg-page);
}
.nf-code {
  font-size: 96px;
  font-weight: 900;
  line-height: 1;
}
.nf-text {
  color: var(--text-secondary);
  margin: 0 0 10px;
}
</style>
