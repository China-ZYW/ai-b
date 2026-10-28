<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

// 底部悬挂的 ICP 备案条：全站可见（公开页/认证页均覆盖，满足备案展示要求），
// 仅「全屏大屏」(route.meta.fullscreen) 隐藏，避免遮挡可视化主体。
// 备案主体与号码经 VITE_ICP_COMPANY / VITE_ICP_NUMBER 配置；未配置时整条隐藏（开源默认不展示任何主体信息）。
//
// 布局：整屏底部满宽（left:0; right:0）；与左侧导航栏之间留出空隙，菜单栏不触底
// （由布局容器 padding-bottom 让出备案条高度，侧栏随之缩短、不再延伸到底部）。
const route = useRoute()
const icpCompany = (import.meta.env.VITE_ICP_COMPANY as string | undefined) || ''
const icpNumber = (import.meta.env.VITE_ICP_NUMBER as string | undefined) || ''
const visible = computed(() => !route.meta.fullscreen && !!(icpCompany || icpNumber))
</script>

<template>
  <footer v-if="visible" class="icp-footer">
    <!-- 备案文案 -->
    <span class="icp-text">
      <span v-if="icpCompany" class="icp-company">{{ icpCompany }}</span>
      <span v-if="icpCompany && icpNumber" class="icp-sep">·</span>
      <a
        v-if="icpNumber"
        class="icp-link"
        href="https://beian.miit.gov.cn/"
        target="_blank"
        rel="noopener noreferrer"
      >{{ icpNumber }}</a>
    </span>
  </footer>
</template>

<style scoped>
.icp-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 34px;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  /* 深水底色：与左侧导航栏（克莱因蓝渐变）色调一致，贴合处无缝 */
  background: linear-gradient(180deg, rgba(10, 22, 58, 0.94) 0%, rgba(0, 22, 79, 0.96) 100%);
  color: rgba(255, 255, 255, 0.82);
  font-size: 12px;
  line-height: 1;
  letter-spacing: 0.2px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  overflow: hidden;
  user-select: none;
  backdrop-filter: blur(6px);
}

/* ---------- 备案文案 ---------- */
.icp-text {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
}
.icp-company {
  white-space: nowrap;
}
.icp-sep {
  opacity: 0.5;
}
.icp-link {
  color: #7fb2ff;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.2s ease;
}
.icp-link:hover {
  color: #a9ccff;
  text-decoration: underline;
}

@media (max-width: 640px) {
  .icp-footer {
    font-size: 11px;
    gap: 6px;
  }
  .icp-company {
    max-width: 46%;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
