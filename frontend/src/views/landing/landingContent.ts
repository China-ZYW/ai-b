/**
 * 落地页静态文案数据（T7.3 自 LandingView 逐字搬移）：
 * 导航锚点/功能特性/三步上手/典型场景/截图轮播/信任数据条/MCP 生态/技术架构。
 * 纯常量无逻辑，各 section 组件按需 import；文案改动只动本文件。
 */

export const navItems = [
  { id: 'features', label: '产品功能' },
  { id: 'arch', label: '技术架构' },
  { id: 'shots', label: '产品截图' },
  { id: 'mcp', label: 'MCP 开放' },
  { id: 'try', label: '在线体验' },
  { id: 'contact', label: '联系我们' }
]

// 产品功能特性
export const features = [
  { icon: '💬', title: '对话式问数', desc: '用自然语言提问，自动生成 SQL 并返回可视化图表，零代码取数。' },
  { icon: '📊', title: '智能驾驶舱', desc: '拖拽搭建实时数据看板，支持多数据源聚合与定时刷新。' },
  { icon: '📈', title: '指标库管理', desc: '沉淀企业核心指标口径，统一计算逻辑，告别口径不一。' },
  { icon: '🔔', title: '智能预警', desc: '为关键指标设置阈值规则，异常自动推送至站内信与多端渠道。' },
  { icon: '🖥️', title: '数据大屏', desc: '一键发布全屏可视化大屏，适配大屏展示与对外分享。' },
  { icon: '🔌', title: '开放 MCP', desc: '签发个人访问令牌，让豆包 / Cursor 等 AI 客户端直连问数。' },
  { icon: '🤝', title: '组织协同', desc: '多成员、多角色协作，邀请入组、共享看板与查询。' },
  { icon: '🛡️', title: '权限治理', desc: '行列级数据授权与字段脱敏，细粒度权限矩阵可审计。' }
]

// 三步上手
export const steps = [
  { no: '01', title: '接入数据源', desc: '连接 MySQL、PostgreSQL、Excel 等业务库，自动识别表结构与字段含义，无需迁移数据。' },
  { no: '02', title: '对话即问数', desc: '用中文提问，自动生成可信 SQL 与图表；沉淀指标口径，团队复用同一套计算逻辑。' },
  { no: '03', title: '洞察与协作', desc: '搭建驾驶舱、设置智能预警，邀请成员共享看板，让数据真正驱动决策。' }
]

// 典型场景
export const scenarios = [
  { icon: '🛒', title: '电商与零售', desc: '销售趋势、品类结构、复购分析，运营随时用一句话拉取最新经营数据。' },
  { icon: '📞', title: '销售与客户', desc: '业绩看板、线索转化、客户分层，让销售管理者告别手工报表。' },
  { icon: '💰', title: '财务与经营', desc: '应收应付、现金流、利润归因，财务口径统一、核对更高效。' },
  { icon: '👥', title: '人力与组织', desc: '人效、离职率、编制执行，HR 用自然语言快速完成人力盘点。' },
  { icon: '🏭', title: '生产与供应链', desc: '产能、库存周转、交付准时率，异常指标自动预警推送。' },
  { icon: '📣', title: '增长与运营', desc: '渠道转化、活动效果、留存分析，增长团队自助取数不排队。' }
]

// 产品截图轮播（图置于 public/screenshots/，按用户提供的截图顺序）
export const shots = [
  { src: '/screenshots/shot-1.png', title: '智能驾驶舱', desc: '教育趋势大屏：实时指标、趋势与多维归因一屏尽览。' },
  { src: '/screenshots/shot-2.png', title: '对话问数', desc: '自然语言提问，秒级返回可信 SQL、表格与可视化结论。' },
  { src: '/screenshots/shot-3.png', title: '对话嵌入', desc: 'iframe 一行代码嵌入第三方系统，免开发对接。' },
  { src: '/screenshots/shot-4.png', title: '问数调优', desc: '语义层标注字段含义与同义词，问数准确率持续提升。' },
  { src: '/screenshots/shot-5.png', title: '分享管理', desc: '分析结果一键生成链接，可随时回收、权限可控。' },
  { src: '/screenshots/shot-6.png', title: '分享报告', desc: '免登录打开分享页，图表与洞察原样呈现。' },
  { src: '/screenshots/shot-7.png', title: '问数调优中心', desc: '语义层集中管理指标、同义词、口径表达式与数据血缘，问数准确率持续提升。' }
]

// 信任数据条（示意，展示产品能力量级）
export const stats = [
  { num: '10+', label: '支持数据源类型' },
  { num: '200ms', label: '级问数响应' },
  { num: '99.9%', label: '服务可用性' },
  { num: '0', label: '代码取数门槛' }
]

// MCP 开放生态：真实连接器实测截图（public/screenshots/）
export const mcpConnectors = [
  {
    src: '/screenshots/mcp-workbuddy.png',
    name: 'WorkBuddy 连接器',
    desc: '在 WorkBuddy 里启用 aibi 连接器，对话直接查询订单月度数据——自动出表格、趋势图与结论，一句话完成经营盘点。',
    tag: '一句话问数'
  },
  {
    src: '/screenshots/mcp-doubao.png',
    name: '豆包 MCP',
    desc: '豆包通过标准 MCP 协议拉取数据，自动生成《订单月度数据分析报告》PDF——指标、趋势与归因一次性成稿。',
    tag: '自动出报告'
  }
]
export const mcpSteps = [
  { no: '01', title: '签发访问令牌', desc: '控制台一键签发个人访问令牌（PAT），随时可吊销，令牌即身份。' },
  { no: '02', title: '配置 MCP 端点', desc: '在豆包 / WorkBuddy / Cursor 等 AI 客户端填入 MCP 地址与令牌，即插即用。' },
  { no: '03', title: '对话即取数', desc: '在 AI 客户端用自然语言问业务库，返回表格、图表乃至成稿报告。' }
]
export const mcpCaps = [
  '标准 MCP 协议 · Streamable HTTP',
  'SSE 实时进度回传，长任务不掉线',
  '按角色鉴权 · 字段级脱敏',
  '每次调用全量审计，配额可控'
]

// 项目架构（对外展示系统组织与技术选型，来源 docs/02-architecture）
export const archLayers = [
  { title: '用户接入层', items: ['Vue3 Web 控制台', '开放 MCP 客户端', '免登录分享页'] },
  { title: '应用服务层', items: ['Spring Boot 模块单体', '用户 / 数据源', '问数 / 指标 / 驾驶舱', '预警 / 报告 / 分享'] },
  { title: '核心能力层', items: ['NL2SQL 引擎', '语义层 / Schema 链接', '图表推荐', '数据源适配器'] },
  { title: '数据存储层', items: ['MySQL 元数据', 'Redis 缓存 / 队列', '用户业务库（只读）', 'LLM API'] }
]
export const archStack = [
  { group: '前端', items: ['Vue 3 + TypeScript', 'Vite', 'Element Plus', 'Apache ECharts', 'Pinia / Axios'] },
  { group: '后端', items: ['Java 17 + Spring Boot 3.2', 'Spring Security + JWT', 'MyBatis-Plus', 'HikariCP', 'JSqlParser 校验'] },
  { group: '数据与中间件', items: ['MySQL 8.0（元数据）', 'Redis 7（缓存 / MQ）', 'Docker Compose', 'Nginx 反向代理'] },
  { group: 'AI 与集成', items: ['DeepSeek / 通义千问', 'SSE 流式返回', '标准 MCP 协议', '数据源只读护栏'] }
]
