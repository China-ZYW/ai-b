/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
import type { ChartStyle, ChartSpec, QueryResult } from './query'

/* ============================ 智能预警（组织设置 · 预警） ============================ */

/** 预警归因摘要中的一个头部贡献成员（只回答"是谁"，不承载完整归因明细）。 */
export interface AlertDriverVO {
  /** 维度成员值（如"东北"）。 */
  member?: string | null
  /** 成员展示名（语义层别名优先）。 */
  label?: string | null
  /** 拉动贡献 = 本期 − 基期（正=拉动，负=拖累）。 */
  delta?: number | null
  /** 贡献占总变化的比例（0~1）。 */
  share?: number | null
}

/**
 * 预警触发时预跑的归因摘要（P1「预警触发预跑归因」）。
 * 成功时 reason 为 null 且 drivers 非空；无法归因时只有 reason（指标非业务键 / 未沉淀维度 / 预跑失败）。
 */
export interface AlertAttributionVO {
  metricId?: number | null
  metricName?: string | null
  /** 实际使用的归因维度列名。 */
  dimension?: string | null
  dimensionLabel?: string | null
  /** 对比基准 MOM/YOY/PEAK。 */
  baseline?: string | null
  currentPeriod?: string | null
  basePeriod?: string | null
  /** 总变化 = 本期合计 − 基期合计。 */
  totalDelta?: number | null
  /** 头部贡献成员（按 |delta| 降序，最多 3 个）。 */
  drivers?: AlertDriverVO[] | null
  insight?: string | null
  /** 归因置信度（0~1）。 */
  confidence?: number | null
  /** 门控 DIRECT/CLARIFY/BLOCK。 */
  gate?: string | null
  /** 未产生归因的原因；成功时为 null。 */
  reason?: string | null
}

/**
 * 预警规则视图对象（/api/org/alerts/rules）。
 * metricKey 支持内置平台键（query.count.24h / query.fail.count.24h / query.fail.rate.24h / datasource.count）
 * 与业务指标键 metric:&lt;id&gt;（对应指标库中已存指标的首行首列值）。
 */
export interface AlertRuleVO {
  id: number
  name?: string | null
  description?: string | null
  metricKey?: string | null
  /** 比较算子 GT/LT/GTE/LTE。 */
  operator?: string | null
  threshold?: number | null
  /** 严重级别 INFO/WARN/CRITICAL。 */
  severity?: string | null
  enabled?: boolean | null
  /** 通知渠道 LOG/EMAIL（SMS/WEBHOOK 为后端扩展位，选到会回落兜底渠道）。 */
  notifyChannel?: string | null
  /** 通知目标：EMAIL=收件人（逗号分隔）；预留 WEBHOOK=URL、SMS=手机号。 */
  notifyTarget?: string | null
  /** 去重窗口（分钟）：窗口内已触发则不重复。 */
  windowMinutes?: number | null
  /** 触发时是否自动预跑归因（仅业务指标 metric:<id> 生效）。 */
  autoAttribution?: boolean | null
  /** 归因维度（物理列名）；空=用指标沉淀的默认维度。 */
  attributionDimension?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 预警规则创建/更新表单。 */
export interface AlertRuleForm {
  name?: string | null
  description?: string | null
  metricKey?: string | null
  operator?: string | null
  threshold?: number | null
  severity?: string | null
  enabled?: boolean | null
  notifyChannel?: string | null
  /** 通知目标：EMAIL=收件人（逗号/分号分隔）。渠道为邮件时必填。 */
  notifyTarget?: string | null
  windowMinutes?: number | null
  /** 触发时是否自动预跑归因。 */
  autoAttribution?: boolean | null
  /** 归因维度（物理列名）；空=用指标沉淀的默认维度。 */
  attributionDimension?: string | null
}

/** 预警触发记录视图对象（/api/org/alerts/records）。 */
export interface AlertRecordVO {
  id: number
  ruleId?: number | null
  ruleName?: string | null
  metricKey?: string | null
  value?: number | null
  operator?: string | null
  threshold?: number | null
  severity?: string | null
  /** FIRED/ACK/RESOLVED。 */
  status?: string | null
  message?: string | null
  notified?: boolean | null
  /** 触发时预跑的归因摘要（未开启/未产生时为 null）。 */
  attribution?: AlertAttributionVO | null
  createdAt?: string | null
}

/** 手动检测结果（POST /api/org/alerts/detect）。 */
export interface AlertDetectResult {
  tenantId?: number | null
  fired?: number | null
}

/* ============================ 站内信收件箱（/api/messages） ============================ */

/** 站内信视图对象（/api/messages）。read=true 表示已读；uid 为接收人本人，后端按身份强制收敛不外露。 */
export interface NotifyMessageVO {
  id: number
  /** 接收人 uid（与当前登录者一致）。 */
  uid?: number | null
  /** 来源预警规则 id（非预警产生的站内信为 null）。 */
  ruleId?: number | null
  /** 来源预警触发记录 id（非预警产生的站内信为 null）。 */
  recordId?: number | null
  /** INFO/WARN/CRITICAL。 */
  severity?: string | null
  title?: string | null
  content?: string | null
  /** 是否已读。 */
  read?: boolean | null
  createdAt?: string | null
}

/** 单个 widget 的视觉样式覆盖（存 layout[i].style，随 layout JSON 落库）。空字段=跟随大屏主题。 */
export interface WidgetStyle {
  /** 卡片背景（CSS background）。 */
  cardBg?: string | null
  /** 卡片边框色。 */
  borderColor?: string | null
  /** 卡片标题文字色。 */
  titleColor?: string | null
  /** 卡片圆角 px。 */
  radius?: number | null
  /** 强调色（本组件的数值高亮 / 徽标）。 */
  accent?: string | null
}

/**
 * 大屏级自定义样式覆盖（存 dashboard.style_config JSON）。
 * 所有字段可选，空/未设 = 跟随所选主题；与 {@link DashboardTheme} 令牌同名者直接覆盖。
 */
export interface DashboardStyleConfig {
  /** 顶栏背景（CSS background）。 */
  headerBg?: string | null
  /** 卡片背景（CSS background）。 */
  cardBg?: string | null
  /** 卡片边框色。 */
  cardBorder?: string | null
  /** 强调色（LIVE 徽章 / 时钟 / 按钮高光）。 */
  accent?: string | null
  /** 主标题文字色。 */
  textPrimary?: string | null
  /** 次要文字色。 */
  textSecondary?: string | null
  /** 弱文字色（占位提示）。 */
  textMuted?: string | null
  /** 字体族（CSS font-family，如 "'Microsoft YaHei', sans-serif"）。 */
  fontFamily?: string | null
  /** 字号比例（0.8~1.4，1=默认；按比例缩放标题/时钟/卡片标题等关键文本）。 */
  fontScale?: number | null
  /** 卡片圆角 px（全局，逐组件可再覆盖）。 */
  cardRadius?: number | null
  /**
   * 是否显示指标（widget）卡片边框。false=隐藏卡片外边框；true/缺省=跟随主题边框色显示。
   * 仅控制外框，不影响卡片背景与标题分隔线。
   */
  showCardBorder?: boolean | null
  /**
   * 自由画布模式开关（V4.44）：true=整屏进入百分比绝对定位画布，弃用 12 列整数网格，
   * widget 改用 fx/fy/fw/fh 百分比坐标自由摆放/缩放。false/缺省=经典网格模式。
   */
  free?: boolean | null
  /**
   * 顶栏 Logo 图片地址（可选）：大屏 / 公开屏顶栏左侧渲染该图片作为品牌标识；空=不显示 Logo（仅标题）。
   * 值为可公开访问的图片 URL（通常由大屏素材上传接口返回）。存于 styleConfig JSON，无需改表。
   */
  logo?: string | null
}

/** 驾驶舱网格布局单元（12 列网格，与 grid-layout-plus 的 {i,x,y,w,h} 对齐）。metricId 空=未绑定占位槽。
 *  chartStyle 为 widget 级图表标注覆盖：同一指标被多个 widget 引用时可各自标不同轴名/单位。
 *  style 为 widget 级视觉样式覆盖（卡片/标题/强调色/圆角）。 */
/**
 * 信息图（AntV Infographic）配置：模板 + 主题 + 数据项要点。
 *
 * <p>与数据图表（绑定 metricId 实时跑 SQL）**互补而非替代**：信息图是**内容驱动**的叙事型组件
 * （流程 / 时间线 / 对比 / 要点列表），补的是"这一屏在讲什么"——结论、口径说明、流程叙事。
 * 配置整体放在 layout 的 widget 里，因此**不需要新增任何数据库字段**（layout 本就是 MEDIUMTEXT JSON）。
 */
export interface InfographicSpec {
  /** 引擎模板名（内置约 200 个，如 list-row-simple-horizontal-arrow）。 */
  template: string
  /** 引擎主题名；留空则跟随驾驶舱主题（前端按 dark/light 映射后传入）。 */
  theme?: string | null
  /** 数据项要点（flat 模式：列表 / 流程 / 金字塔 / 扇形 / 瀑布 / 关系等）。 */
  items: InfographicItem[]
  /** 数据形态：flat=单组要点；grouped=分组（对比 / 层级类，每个分组含 children）。缺省 flat，向后兼容。 */
  mode?: 'flat' | 'grouped' | null
  /** 分组数据（mode=grouped 时生效）：每个分组是一根节点，children 为其下要点。 */
  groups?: InfographicGroup[] | null
}

/** 信息图单个数据项：label 为主文案，desc 为补充，value 为可选数值。 */
export interface InfographicItem {
  label: string
  desc?: string | null
  /** 可选数值：未绑定指标时作为手填兜底；绑定指标时运行时被绑定字段实时值覆盖。 */
  value?: string | number | null
  /** 绑定指标 id（可选）：非空时运行时从指标实时结果按字段取数覆盖 value/label/desc。空=用手填。 */
  metricId?: number | null
  /**
   * 取值规则（可选，缺省 firstCell，向后兼容）：仅在不指定下方字段绑定时生效。
   * - firstCell：首行首列（默认）
   * - scalar：单行单列 → 该单元格
   * - rowSum[:(列名)]：对首列（或指定列）求和
   * - column:<列名>：首行指定列的值
   */
  extract?: string | null
  /**
   * 指标字段绑定（V4.36 选项②）：item 绑定指标后，按列名分别取首行值填入下列字段，优先级高于 extract。
   * 数值字段：取该列首行值（转有限数）填入 value。
   * 文案字段：取该列首行值填入 label。
   * 说明字段：取该列首行值填入 desc。
   * 三者均为空时回落 extract；均未绑定指标时回落手填 value。
   */
  valueField?: string | null
  labelField?: string | null
  descField?: string | null
}

/** 信息图分组（对比 / 层级类模板的根节点）：label 为主文案（如 S/W/O/T、方案 A），children 为组内要点。 */
export interface InfographicGroup {
  label: string
  desc?: string | null
  /** 分组根节点数值（预留：当前引擎渲染以 children 数值为主；group 级绑定指标的实时值可落此处供后续扩展）。 */
  value?: string | number | null
  /** 分组根节点也可绑定指标（预留）。 */
  metricId?: number | null
  extract?: string | null
  /** 分组根节点字段绑定（语义同 item）：valueField / labelField / descField。 */
  valueField?: string | null
  labelField?: string | null
  descField?: string | null
  children: InfographicItem[]
}

/**
 * 图片/图标组件配置（仅 kind=image 时生效）：自由放置的 logo/图标/装饰图片，不绑定指标、不取数。
 * 存于 layout[i].image（随 layout JSON 落库，无需改表）。
 */
export interface ImageWidgetSpec {
  /** 图片地址：大屏素材上传返回的公开 URL，或外链。 */
  url: string
  /**
   * 填充方式：contain=完整保留不裁切（默认，适合 logo/图标）；cover=裁满容器；fill=拉伸铺满容器。
   */
  fit?: 'contain' | 'cover' | 'fill' | null
  /** 圆形裁切（用于 logo/头像类图标）。 */
  round?: boolean | null
  /** 替代文本（无障碍 / 加载失败兜底文案）。 */
  alt?: string | null
}

/**
 * 列表组件配置（仅 kind=list 时生效）：绑定指标、取数后把结果渲染为可滚动榜单。
 * 列名取自查询结果的 columns（如指标绑定 SQL 的 SELECT 字段名），留空则按顺序取前 1~3 列。
 * 存于 layout[i].list（随 layout JSON 落库，无需改表）。
 */
export interface ListWidgetSpec {
  /** 主展示列（榜单左侧主文案，如"商品名/地区"）。留空取结果第一列。 */
  primaryField?: string | null
  /** 次展示列（主文案下方灰色辅助文案，如"编码/负责人"）。留空不渲染。 */
  secondaryField?: string | null
  /** 数值列（榜单右侧高亮数值，如"销售额/数量"）。留空不渲染数值。 */
  valueField?: string | null
  /** 是否开启自动滚动（跑马灯无缝循环）。默认 false=静态可手动滚动。 */
  autoScroll?: boolean | null
  /** 滚动一圈耗时（秒）。越大越慢。autoScroll=true 时生效，默认 30。 */
  scrollSpeed?: number | null
}

export interface WidgetLayout {
  i: string
  metricId?: number | null
  x: number
  y: number
  w: number
  h: number
  title?: string | null
  chartStyle?: ChartStyle | null
  style?: WidgetStyle | null
  /**
   * 组件类型：chart=数据图表（默认，绑定指标实时取数）；infographic=信息图（内容驱动，不取数）；
   * image=图片/图标（自由放置的 logo/图标/装饰图片，不绑定指标、不取数）。
   * **缺省即 chart**——既有驾驶舱的 layout 没有此字段，行为完全不变（向后兼容的关键）。
   */
  kind?: 'chart' | 'infographic' | 'image' | 'list' | null
  /** 信息图配置，仅 kind=infographic 时生效。 */
  infographic?: InfographicSpec | null
  /**
   * 图片/图标组件配置，仅 kind=image 时生效：自由放置的 logo/图标/装饰图片，不绑定指标、不取数。
   * 形如 { url, fit:'contain'|'cover'|'fill', round?:boolean, alt?:string }；url 为可公开访问的图片地址。
   */
  image?: ImageWidgetSpec | null
  /**
   * 列表组件配置，仅 kind=list 时生效：绑定指标、取数后把结果渲染为可滚动榜单。
   * 形如 { primaryField, secondaryField, valueField, autoScroll?:boolean, scrollSpeed?:number }。
   */
  list?: ListWidgetSpec | null
  /**
   * 自由画布模式（DashboardStyleConfig.free=true）下的百分比坐标（0~100），可空。
   * fx/fy=左上角横/纵坐标（占画布宽/高百分比），fw/fh=宽/高（占画布宽/高百分比）。
   * 非自由画布模式下为 null，渲染落点改用 x/y/w/h 整数网格。
   */
  fx?: number | null
  fy?: number | null
  fw?: number | null
  fh?: number | null
}

/** 驾驶舱视图对象。layout 为 WidgetLayout 数组；theme=light/dark；isScreen 1=全屏大屏。
 *  styleConfig 为大屏级自定义样式覆盖（卡片/字体/配色）。 */
export interface DashboardVO {
  id: number
  name: string
  description?: string | null
  templateCode?: string | null
  /** 所属分组（dashboard_group.id；空=未分组）。移动归属走专用接口，不随编辑器保存变更。 */
  groupId?: number | null
  theme?: string | null
  cols?: number | null
  refreshSeconds?: number | null
  isScreen?: number | null
  background?: string | null
  styleConfig?: DashboardStyleConfig | null
  layout?: WidgetLayout[] | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 驾驶舱创建 / 更新表单（含布局保存）。groupId 仅 create 生效（update 不携带以免清空分组）。 */
export interface DashboardForm {
  name: string
  description?: string | null
  theme?: string | null
  cols?: number | null
  refreshSeconds?: number | null
  isScreen?: number | null
  background?: string | null
  styleConfig?: DashboardStyleConfig | null
  layout?: WidgetLayout[] | null
  groupId?: number | null
}

/** 驾驶舱分组视图对象（扁平列表，前端组树；parentId 空=根级）。 */
export interface DashboardGroupVO {
  id: number
  name: string
  parentId?: number | null
  sort?: number | null
  createdAt?: string | null
}

/** 分组创建 / 更新表单（parentId 空=根级；更新时改 parentId 即移动子树）。 */
export interface DashboardGroupForm {
  name: string
  parentId?: number | null
}

/** 驾驶舱移动分组表单（groupId 空=移出分组）。 */
export interface DashboardMoveForm {
  groupId?: number | null
}

/** 背景素材上传结果：返回可公开访问的图片 URL（供大屏背景 url() 引用）。 */
export interface MaterialUploadResult {
  url: string
}

/**
 * AI 布局顾问：单个组件的数据形态特征摘要。
 * 只传统计量（行数/类目数/系列数/标签长度），**不外发明细数据行**。
 */
export interface AiLayoutFeature {
  /** 组件 key，与 layout[].i 一致，用于回填坐标。 */
  i: string
  name?: string | null
  chartType?: string | null
  /** 前端判定视觉角色（KPI/TREND/RANKING…），作为模型参考建议。 */
  roleHint?: string | null
  rowCount?: number | null
  colCount?: number | null
  categories?: number | null
  series?: number | null
  maxLabel?: number | null
  single?: boolean | null
}

/** AI 布局槽位：大模型优化后的网格坐标 */
export interface AiLayoutSlot {
  i: string
  x: number
  y: number
  w: number
  h: number
  reason?: string | null
}

/**
 * AI 智能设计请求：组件特征 + 确定性算法基线 + 可选风格指令。
 * 基线由前端 smartLayout 引擎算出，后端不复制算法，只做「LLM 建议 + 严格校验」。
 */
export interface AiLayoutRequest {
  cols?: number | null
  isScreen?: number | null
  /** 用户的风格指令，如「科技感深色」「给领导看的经营看板」。 */
  intent?: string | null
  items: AiLayoutFeature[]
  baseline?: AiLayoutSlot[] | null
}

/** AI 智能设计结果：source=llm 才使用 slots/样式，否则沿用前端基线。 */
export interface AiLayoutVO {
  slots: AiLayoutSlot[]
  /** 大屏级样式白名单键值（已由后端校验为合法 CSS）。 */
  style?: Record<string, unknown> | null
  /** 建议整屏背景（CSS background）。 */
  screenBg?: string | null
  summary?: string | null
  /** 'llm'=大模型方案；'algorithm'=已回退确定性算法（slots 为空）。 */
  source: string
  message?: string | null
}

/** 一键自动布局表单：给定指标生成 12 列均衡网格驾驶舱。 */
export interface AutoDesignForm {
  name: string
  metricIds: number[]
  description?: string | null
  theme?: string | null
  refreshSeconds?: number | null
  isScreen?: number | null
}

/** 从内置模板实例化表单：拷骨架布局，按顺序把指标绑定到槽位。 */
export interface FromTemplateForm {
  name: string
  templateCode: string
  metricIds?: number[] | null
  description?: string | null
  theme?: string | null
  refreshSeconds?: number | null
  isScreen?: number | null
}

/** 驾驶舱内置模板视图对象。layout 为骨架布局（含占位槽，metricId 空）。 */
export interface DashboardTemplateVO {
  id: number
  code: string
  name: string
  category?: string | null
  description?: string | null
  icon?: string | null
  theme?: string | null
  layout?: WidgetLayout[] | null
}

/** 驾驶舱模板创建 / 更新表单（仅超管）。 */
export interface DashboardTemplateForm {
  code: string
  name: string
  category?: string | null
  description?: string | null
  icon?: string | null
  theme?: string | null
  layout?: WidgetLayout[] | null
}

/** widget 渲染数据（大屏批量刷新）：布局坐标 + 绑定指标实时结果。result 空=未绑定或刷新失败。
 *  chartStyle 为本 widget 的标注覆盖，渲染时优先于 chartSpec.style。
 *  style 为本 widget 的视觉样式覆盖（卡片/标题/强调色/圆角），渲染时覆盖大屏主题。 */
export interface WidgetDataVO {
  i: string
  metricId?: number | null
  title?: string | null
  x: number
  y: number
  w: number
  h: number
  chartSpec?: ChartSpec | null
  chartStyle?: ChartStyle | null
  style?: WidgetStyle | null
  /** 组件类型（chart / infographic / image / list）；image 组件据此走不取数的图片渲染路径，list 走绑指标的榜单渲染路径。 */
  kind?: 'chart' | 'infographic' | 'image' | 'list' | null
  /** 信息图配置，仅 kind=infographic 时生效。 */
  infographic?: InfographicSpec | null
  /** 图片/图标组件配置，仅 kind=image 时生效。 */
  image?: ImageWidgetSpec | null
  /** 列表组件配置，仅 kind=list 时生效。 */
  list?: ListWidgetSpec | null
  /** 自由画布模式百分比坐标（0~100，可空）；非自由画布为 null。须与后端 WidgetDataVO 同步新增。 */
  fx?: number | null
  fy?: number | null
  fw?: number | null
  fh?: number | null
  result?: QueryResult | null
}

/** 驾驶舱下钻/联动筛选条件（V4.44）：点击某 widget 维度成员后整屏按该维度切片。
 *  dim=维度列名（如 region/category），value=维度成员值（如"华东"/2024）；与后端 DashboardFilter 同名同义。 */
export interface DashboardFilter {
  dim: string
  value: string | number | null
}

/** 驾驶舱整体数据（大屏按 refreshSeconds 轮询）。 */
export interface DashboardDataVO {
  dashboard: DashboardVO
  widgets: WidgetDataVO[]
}

