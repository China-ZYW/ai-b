<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 组织切换器（C 缺口前端）：仅当当前自然人是多组织身份（可切换组织 >1）时显示。
 * 下拉展示各组织名 + 组织角色，点击切换走后端「换令牌」路径，前端换存并重拉权限快照。
 */
const userStore = useUserStore()
const switching = ref(false)

const current = computed(() => userStore.tenants.find((t) => t.current) ?? null)
const currentName = computed(() => current.value?.tenantName ?? '当前组织')

async function onSwitch(tenantId: number): Promise<void> {
  if (tenantId === userStore.currentTenantId) return
  switching.value = true
  try {
    await userStore.switchOrg(tenantId)
    const t = userStore.tenants.find((x) => x.tenantId === tenantId)
    ElMessage.success(`已切换到「${t?.tenantName ?? '组织'}」`)
  } catch (e) {
    ElMessage.error((e as Error)?.message || '切换组织失败')
  } finally {
    switching.value = false
  }
}
</script>

<template>
  <el-dropdown
    v-if="userStore.multiOrg"
    trigger="click"
    class="org-switcher"
    :disabled="switching"
    @command="onSwitch"
  >
    <button class="org-chip" :disabled="switching">
      <el-icon :size="14"><OfficeBuilding /></el-icon>
      <span class="org-name truncate">{{ currentName }}</span>
      <el-icon :size="12"><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="t in userStore.tenants"
          :key="t.tenantId"
          :command="t.tenantId"
          :disabled="t.current"
        >
          <el-icon v-if="t.current"><Check /></el-icon>
          <span v-else class="org-dot" />
          <span class="org-item-name">{{ t.tenantName || '组织' }}</span>
          <span v-if="t.orgRoles" class="org-item-roles">{{ t.orgRoles }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.org-switcher {
  flex-shrink: 0;
}
.org-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--border-light);
  background: var(--ikb-pale);
  color: var(--ikb);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.org-chip:hover:not(:disabled) {
  filter: brightness(0.98);
}
.org-chip:disabled {
  opacity: 0.6;
  cursor: default;
}
.org-name {
  max-width: 120px;
}
.org-item-name {
  margin-left: 4px;
}
.org-item-roles {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-secondary);
}
.org-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
}
</style>
