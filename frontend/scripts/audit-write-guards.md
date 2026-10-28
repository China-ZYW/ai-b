# 写操作护栏审计（自动生成，勿手工编辑）

- 含写操作的前端文件：**45** 个，写调用共 **127** 处
- 护栏采用：统一护栏 **26** / 手写 **0** / 无 **19**
- 异步开关（model-value 驱动、change 发请求）共 **6** 个；缺 loading **0**、缺 disabled **0**

| 文件 | 写调用 | 护栏 | 异步开关 | 缺 :loading | 缺 :disabled |
|---|---:|---|---:|---:|---:|
| `views/metric/MetricView.vue` | 8 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/org/OrgAlertsView.vue` | 8 | ✅ 统一护栏 | 1 | 0 | 0 |
| `components/tuning/SemanticPanel.vue` | 7 | ✅ 统一护栏 | 0 | 0 | 0 |
| `stores/user.ts` | 7 | ❌ 无 | 0 | 0 | 0 |
| `views/datasource/DataSourceView.vue` | 7 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/LlmConfigView.vue` | 6 | ✅ 统一护栏 | 1 | 0 | 0 |
| `views/org/OrgMembersView.vue` | 6 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/UserAdminView.vue` | 5 | ✅ 统一护栏 | 1 | 0 | 0 |
| `views/dashboard/DashboardListView.vue` | 5 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/tuning/FewShotPanel.vue` | 4 | ✅ 统一护栏 | 1 | 0 | 0 |
| `stores/datasource.ts` | 4 | ❌ 无 | 0 | 0 | 0 |
| `views/admin/GlobalConfigView.vue` | 4 | ✅ 统一护栏 | 1 | 0 | 0 |
| `views/admin/TenantAdminView.vue` | 4 | ✅ 统一护栏 | 1 | 0 | 0 |
| `components/tuning/ChartRulePanel.vue` | 3 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/tuning/ThemePanel.vue` | 3 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/ContentOpsView.vue` | 3 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/ManualAdminView.vue` | 3 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/dashboard/DashboardEditorView.vue` | 3 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/chat/AttributionPanel.vue` | 2 | ❌ 无 | 0 | 0 | 0 |
| `components/dashboard/DashboardShareDialog.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/tuning/ConfigPanel.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/PlatformRolesView.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/admin/SeatAdminView.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/chat/ChatView.vue` | 2 | ❌ 无 | 0 | 0 | 0 |
| `views/org/OrgInvitationsView.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/org/OrgJoinView.vue` | 2 | ❌ 无 | 0 | 0 | 0 |
| `views/screen/PublicScreenView.vue` | 2 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/share/MySharesView.vue` | 2 | ❌ 无 | 0 | 0 | 0 |
| `components/chat/MetricSaveDialog.vue` | 1 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/chat/ShareDialog.vue` | 1 | ✅ 统一护栏 | 0 | 0 | 0 |
| `components/OrgSwitcher.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `stores/chat.ts` | 1 | ❌ 无 | 0 | 0 | 0 |
| `stores/permission.ts` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/admin/ApiMatrixView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/admin/AuditLogView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/admin/OperationLogView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/admin/ProfileView.vue` | 1 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/auth/LoginView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/auth/RegisterView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/favorite/FavoriteView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/history/HistoryView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/org/OrgAuditView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/screen/ScreenView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
| `views/share/PublicShareView.vue` | 1 | ✅ 统一护栏 | 0 | 0 | 0 |
| `views/tuning/LineageView.vue` | 1 | ❌ 无 | 0 | 0 | 0 |
