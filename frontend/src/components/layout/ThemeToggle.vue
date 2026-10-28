<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { UI_THEME_OPTIONS, type UiThemeMode } from '@/theme/appTheme'

/**
 * 界面明暗主题切换（账号级持久化）。
 *
 * <p>三态（浅色/深色/跟随系统）而不是"深浅二态开关"：相当一部分用户的系统本身就是深色，
 * 二态开关会逼他们再手动一次且无法随系统作息自动切换；跟随系统才是这类用户的默认值。
 *
 * <p>写入走 {@code userStore.setUiTheme}（后端 {@code app_user.ui_theme}），故换设备/换浏览器
 * 后主题仍在；本地 localStorage 只是首屏镜像，不承担权威。
 */
const userStore = useUserStore()

const current = computed<UiThemeMode>(() => userStore.uiTheme)

const icon = computed(() => {
  if (current.value === 'system') return 'Monitor'
  return userStore.isDarkTheme ? 'Moon' : 'Sunny'
})

async function onPick(m: UiThemeMode): Promise<void> {
  if (m === current.value) return
  await userStore.setUiTheme(m)
}
</script>

<template>
  <el-dropdown trigger="click" @command="(c: UiThemeMode) => onPick(c)">
    <button class="theme-btn" type="button" aria-label="切换界面主题" title="界面主题">
      <el-icon :size="16"><component :is="icon" /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="o in UI_THEME_OPTIONS" :key="o.value" :command="o.value">
          <span class="th-item">
            <el-icon :size="14">
              <Check v-if="o.value === current" />
            </el-icon>
            <span>{{ o.label }}</span>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.theme-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--bg-card);
  color: var(--text-regular);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease;
}
.theme-btn:hover {
  color: var(--ikb);
  border-color: var(--ikb);
  background: var(--ikb-ghost);
}
.th-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 84px;
}
.th-item .el-icon {
  color: var(--ikb);
}
</style>
