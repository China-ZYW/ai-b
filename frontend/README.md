# AI 数据助理 · 前端

对话式问数（NL2SQL）平台前端。**Vue 3 + TypeScript + Vite + Element Plus + Apache ECharts + Pinia**，全站主视觉采用**克莱因蓝（International Klein Blue, `#002FA7`）+ 蓝色渐变**。

---

## 技术栈

| 领域 | 选型 | 版本 |
| --- | --- | --- |
| 框架 | Vue | ^3.5（`<script setup>` + Composition API） |
| 语言 | TypeScript | ~5.6（`strict` + `noUnusedLocals/Parameters`） |
| 构建 | Vite | ^5.4 |
| UI 组件 | Element Plus | ^2.8（+ `@element-plus/icons-vue` 全局注册） |
| 图表 | Apache ECharts | ^5.5 |
| 拖拽网格 | grid-layout-plus | ^1.1（驾驶舱自由布局/缩放） |
| 状态 | Pinia | ^2.2（setup 风格 store） |
| 路由 | Vue Router | ^4.4（懒加载 + 守卫） |
| HTTP | Axios | ^1.7（统一响应解包 + 拦截器） |

---

## 快速开始

```bash
cd frontend
npm install          # 安装依赖
npm run dev          # 开发服务器 → http://localhost:5173
npm run build        # 类型校验(vue-tsc) + 生产构建 → dist/
npm run type-check   # 仅类型校验
npm run preview      # 预览已构建产物
```

> 环境要求：Node ≥ 18（开发验证于 Node 20.19 / npm 10.7）。

---

## 与后端联调

后端 `SecurityConfig` 中 **禁用了 CORS**，因此开发期统一通过 **Vite 代理**转发 `/api`，规避浏览器跨域：

```
浏览器 → http://localhost:5173/api/**  ──(Vite proxy)──▶  http://localhost:8080/api/**
```

- 代理目标默认 `http://localhost:8080`，可用环境变量覆盖：
  ```bash
  # PowerShell
  $env:VITE_BACKEND="http://192.168.1.10:8080"; npm run dev
  ```
- 代理针对 `text/event-stream` 响应关闭缓冲（`cache-control: no-transform`、`x-accel-buffering: no`），保证 **SSE 分步进度实时下发**。

### 启动后端

后端需要 **MySQL**（元数据库 `aibi_meta`，首次连接自动建库 + 执行 DDL + 播种演示账号）。详见 [`../backend/README.md`](../backend/README.md)。启动后即可用演示账号登录：

```
用户名 admin / 密码 → 从启动后台 WARN 日志里取（开源仓不预置硬编码默认口令；
                          也可先在 backend/local.env 里设 AIBI_DEMO_ADMIN_PASSWORD 固定）
                          →  租户 1  →  演示数据源「演示电商库」
```

> 说明：Redis 非必需（配额/Schema 缓存为内存实现）；LLM Key 缺省时问数生成会失败（设置 `DEEPSEEK_API_KEY` 后即可跑通完整 NL2SQL：意图识别→生成 SQL→执行→图表推荐），但登录、数据源、历史、收藏、分享等接口不受影响。

### SSE 鉴权方案

`/api/chat/query/stream` 需 `Authorization: Bearer` 头，而原生 `EventSource` **无法设置自定义请求头**。因此前端采用 **`fetch` + `ReadableStream` 手动解析 `text/event-stream`**（见 [`src/api/sse.ts`](src/api/sse.ts)），无需改动后端。事件协议：

| event | payload | 说明 |
| --- | --- | --- |
| `stage` | `{ stage, message }` | 分步进度：`start/schema/intent/generate/sql/execute/repair/chart/done` |
| `result` | `ChatQueryResponse` | 最终结果（SQL + 列行 + 图表推荐） |
| `error` | `{ code, message }` | 业务错误 |

> **终止语义（重要）**：客户端把 `result` / `error` 视为**逻辑终止事件**——收到即复位发送状态并主动 `reader.cancel()` 关流，**不依赖服务端/代理何时关闭 TCP**。原因：经 Vite 代理时后端 `emitter.complete()` 的关闭未必及时透传（直连时客户端 reader 甚至会抛 `terminated`），若只在连接关闭时才复位，问数结束后「停止」按钮会长时间卡住。

---

## 目录结构

```
frontend/
├─ index.html
├─ vite.config.ts          # 别名 @ → src；/api 代理；manualChunks 分包
├─ tsconfig.json
├─ package.json
└─ src/
   ├─ main.ts              # 注册 ElementPlus / 图标 / pinia / router；样式引入
   ├─ App.vue              # 根组件：router-view + 过渡；启动静默 loadMe/refreshQuota
   ├─ env.d.ts
   ├─ api/                 # 接口层
   │  ├─ request.ts        # axios 实例 + 拦截器（解包 R<T> / 401 跳登录）+ get/post/put/del
   │  ├─ sse.ts            # fetch 版 SSE（askStream，返回 AbortController）
   │  ├─ auth.ts datasource.ts chat.ts history.ts
   │  ├─ favorite.ts quota.ts share.ts
   │  └─ admin.ts metric.ts dashboard.ts dashboardTemplate.ts
   ├─ types/index.ts       # 全局类型（与后端 DTO 精确对齐）+ 错误码 ErrCode
   ├─ types/css-vars.d.ts  # 增强 @vue/runtime-dom CSSProperties，令 :style 支持 --* 自定义属性（须为模块）
   ├─ stores/              # Pinia
   │  ├─ user.ts           # 令牌/资料/配额（localStorage 持久化）
   │  ├─ datasource.ts     # 列表/当前选中/表结构缓存
   │  ├─ chat.ts           # 会话/消息流/SSE 编排
   │  └─ app.ts            # 侧栏折叠等 UI 状态
   ├─ router/index.ts      # 路由表 + 登录守卫（公开页放行）
   ├─ composables/
   │  └─ useChartOption.ts # ChartSpec + QueryResult + 主题令牌 → ECharts option（问题6：配色随主题）
   ├─ theme/
   │  └─ dashboardTheme.ts # 大屏主题预设（问题5：6 套背景/色板联动）+ resolveTheme + THEME_OPTIONS
   ├─ components/
   │  ├─ layout/AppLayout.vue    # 主壳：渐变侧栏 + 顶栏（数据源快选/配额/用户菜单/超管入口）
   │  ├─ layout/AdminLayout.vue  # 超管后台外壳（深色侧栏 + 平台管理顶栏）
   │  ├─ chart/BaseChart.vue     # ECharts 封装（init/resize/dispose，shallowRef）
   │  ├─ chat/                   # ChatInput / ResultPanel / ShareDialog / MetricSaveDialog
   │  └─ dashboard/              # WidgetCard（widget 渲染）· TemplateGallery · DashboardShareDialog（问题2）
   ├─ views/
   │  ├─ auth/            LoginView · RegisterView
   │  ├─ chat/            ChatView（会话侧栏 + SSE 问数 + 结果面板 + 问题4：动态推荐问题 + 问题1：收藏去重）
   │  ├─ datasource/      DataSourceView（增删改查 + 测试连接 + 表结构抽屉 + 问题3：文件上传/预览）
   │  ├─ history/         HistoryView（分页 + 数据源筛选 + 详情/删除）
   │  ├─ favorite/        FavoriteView（收藏卡片 + 去问数深链）
   │  ├─ share/           MySharesView（分享管理）· PublicShareView（公开访问页）
   │  ├─ metric/          MetricView（指标库：预览/改名/删除/加入驾驶舱）
   │  ├─ dashboard/       DashboardListView · DashboardEditorView（grid 拖拽编辑器）
   │  ├─ screen/          ScreenView（全屏数据大屏）· PublicScreenView（问题2：token 公开访问）
   │  ├─ admin/           ProfileView（个人中心）· AdminOverview/Tenant/User/LlmConfig（超管台）
   │  └─ NotFoundView.vue
   ├─ utils/              format.ts（时间/数字/耗时）· storage.ts（令牌/用户读写）
   └─ styles/            variables.css（克莱因蓝令牌）· global.css · element.css（EP 覆写）
```

---

## 路由

| 路径 | 视图 | 鉴权 |
| --- | --- | --- |
| `/login` `/register` | 登录 / 注册 | 公开 |
| `/s/:token` | 公开分享页（查询结果快照，密码/过期/水印） | 公开 |
| `/share/screen/:token` | 公开数据大屏（问题2：凭 token 免登录、实时刷新，可设密码/有效期） | 公开 |
| `/chat` | 对话问数（默认首页） | 需登录 |
| `/datasource` | 数据源管理 | 需登录 |
| `/metrics` | 指标库（预览/改名/删除/加入驾驶舱） | 需登录 |
| `/dashboards` | 驾驶舱列表（空白/一键自动布局/从模板新建） | 需登录 |
| `/dashboards/:id/edit` | 驾驶舱编辑器（grid-layout-plus 拖拽/缩放） | 需登录 |
| `/screen/:id` | 全屏数据大屏（深色克莱因蓝 + 轮询刷新） | 需登录 |
| `/admin/overview` `/admin/tenants` `/admin/users` `/admin/llm` | 超管控制台（概览/租户/账号/大模型配置） | 仅 SUPER_ADMIN |
| `/history` | 查询历史 | 需登录 |
| `/favorite` | 我的收藏 | 需登录 |
| `/shares` | 分享管理 | 需登录 |
| `/profile` | 个人中心 | 需登录 |

守卫逻辑：无令牌访问受保护页 → 跳 `/login?redirect=`；已登录访问登录/注册页 → 回**角色落地页**（超管 `/admin`、普通 `/chat`）；`meta.role='SUPER_ADMIN'` 的路由非超管跳回 `/chat`（后端 `/api/admin/**` 另有 `hasRole("SUPER_ADMIN")` 强校验）。

---

## 克莱因蓝主题

设计令牌集中于 [`src/styles/variables.css`](src/styles/variables.css)，并覆写 Element Plus 的 `--el-color-primary` 系列使组件与主题一致。

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--ikb` | `#002FA7` | 克莱因蓝主色 |
| `--ikb-dark / --ikb-darker` | `#001F70 / #00164F` | 悬停/侧栏深色端 |
| `--ikb-light / --ikb-lighter` | `#1E5AE8 / #4A90F5` | 渐变中/亮端 |
| `--accent-cyan` | `#00C2FF` | 青蓝高光点缀 |
| `--grad-brand` | `135deg #002FA7→#1E5AE8→#4A90F5` | 品牌主渐变（按钮/徽标/标题） |
| `--grad-sidebar` | `185deg #00164F→#002FA7→#0B3FC4` | 侧栏深色渐变 |
| `--grad-auth-bg` | radial + linear 组合 | 登录/分享页大背景 |

图表色板（`useChartOption.ts`）同样以克莱因蓝为首色，柱/面积图使用 `LinearGradient` 渐变填充。

### 数据大屏主题（问题5 / 6）

大屏配色独立于站点克莱因蓝，由 [`src/theme/dashboardTheme.ts`](src/theme/dashboardTheme.ts) 的 **6 套预设**驱动（`dark` 深邃蓝 / `light` 简洁白 / `tech` 科技青 / `emerald` 翡翠绿 / `sunset` 暮光橙 / `purple` 暗夜紫）。每套预设是一组主题令牌（背景 / 标题栏 / 卡片 / 文字 / 图表色板 / 坐标轴 / tooltip）。`buildChartOption(spec, result, theme)` 接收主题令牌，令**图表配色随大屏背景一致**（问题6）；`resolveTheme(key)` 对空值/旧值 `light`/`dark` 安全回落。令牌经 CSS 自定义属性（`--sc-*` / `--wc-*`）下发到 `ScreenView` / `PublicScreenView` / `WidgetCard`，故 `types/css-vars.d.ts` 增强了 `CSSProperties` 以通过 `vue-tsc`。问数结果面板 `ResultPanel` 默认走 `light` 主题，视觉保持不变。

---

## 关键约定

- **统一响应 `R<T>`**：`{ code, message, data, traceId }`，`code=0` 成功；拦截器自动解包 `data`，非 0 弹 `ElMessage` 并 reject；`code=40001` / HTTP 401 → 清登录态并整页跳登录。
- **分页 `PageResult<T>`**：`{ items, total, limit, offset }`。
- **凭证只写不读**：数据源响应仅含 `hasPassword`，编辑时密码留空表示保持原凭证。
- **令牌存储**独立于 `utils/storage.ts`，避免 store ↔ api 循环依赖。
- **构建分包**：`echarts` / `element-plus` / `vendor` 单独 chunk，便于强缓存。

---

## 已对齐的后端契约

所有接口路径、DTO 字段、枚举名、错误码均**逐一核对后端源码**（controller + dto + model），确保联调零偏差。例如：数据源接口为单数 `/api/datasource`；`QueryResult.columns` 为 `string[]`、`rows` 为二维数组；`ChartType ∈ {LINE,BAR,PIE,TABLE}`；SSE stage 描述字段名为 `message`。类型定义见 [`src/types/index.ts`](src/types/index.ts)。
