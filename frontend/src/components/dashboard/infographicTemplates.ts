/**
 * 信息图模板目录（驾驶舱专用，经筛选对「label/desc/value」要点结构兼容良好）。
 *
 * <p><b>来源</b>：AntV Infographic 0.2.20 内置约 120 个模板，名冗长且随版本变动。这里只保留
 * 对引擎数据形态兼容良好、能真正渲染出内容的那几类：消费扁平 {@code items[]} 的「列表 / 流程 /
 * 金字塔 / 扇形 / 瀑布 / 关系」，以及消费分组 {@code items[].children} 的「对比」（SWOT、左右对比）。
 * 高级用户仍可在编辑器的「模板」框手动输入任意模板名使用。
 *
 * <p><b>数据形态</b>：{@code dataShape='grouped'} 的模板需要分组数据（每个分组是一根节点、组内要点
 * 放 {@code children}），编辑器据此自动切换到「分组」填写形态；缺省 flat 即单组要点。
 *
 * <p><b>同步约束</b>：本目录是添加入口与编辑器下拉的唯一数据源；新增一个可用模板只需在此追加一项，
 * 画廊与下拉自动同步，无需改两处。
 */
export type InfographicCategory =
  | 'list'
  | 'sequence'
  | 'pyramid'
  | 'sector'
  | 'waterfall'
  | 'compare'
  | 'relation'

export interface InfographicTemplateMeta {
  /** 引擎模板名（与 built-in.js 注册表一致）。 */
  template: string
  /** 中文展示名（画廊卡片 / 下拉选项）。 */
  name: string
  /** 分类，用于画廊分标签与缩略图形态。 */
  category: InfographicCategory
  /** 一句话说明，帮助挑选。 */
  desc: string
  /** 数据形态：flat=扁平要点；grouped=分组（每个分组含 children）。缺省 flat。 */
  dataShape?: 'flat' | 'grouped'
}

/** 分类中文标签；'all' 仅用于画廊「全部」标签。 */
export const INFO_CATEGORY_LABELS: Record<InfographicCategory | 'all', string> = {
  all: '全部',
  list: '列表',
  sequence: '流程',
  pyramid: '金字塔',
  sector: '扇形',
  waterfall: '瀑布',
  compare: '对比',
  relation: '关系'
}

/** 经筛选的安全模板（对扁平 items[] 友好）。 */
export const INFO_TEMPLATE_CATALOG: InfographicTemplateMeta[] = [
  // —— 列表 ——
  { template: 'list-row-simple-horizontal-arrow', name: '横向箭头列表', category: 'list', desc: '一行一要点，右侧箭头衔接' },
  { template: 'list-column-simple-vertical-arrow', name: '纵向箭头列表', category: 'list', desc: '竖向堆叠，箭头串联' },
  { template: 'list-grid-simple', name: '网格列表', category: 'list', desc: '多列卡片平铺' },
  { template: 'list-grid-badge-card', name: '网格徽章卡', category: 'list', desc: '卡片带徽章标识' },
  { template: 'list-grid-compact-card', name: '网格紧凑卡', category: 'list', desc: '紧凑网格卡片' },
  { template: 'list-grid-ribbon-card', name: '缎带网格卡', category: 'list', desc: '带缎带装饰的卡片' },
  { template: 'list-grid-progress-card', name: '进度网格卡', category: 'list', desc: '卡片内嵌进度条' },
  { template: 'list-row-horizontal-icon-arrow', name: '图标箭头行', category: 'list', desc: '行内图标 + 箭头' },
  { template: 'list-row-circular-progress', name: '环形进度行', category: 'list', desc: '行内环形进度' },
  // —— 金字塔 ——
  { template: 'list-pyramid-compact-card', name: '金字塔紧凑卡', category: 'pyramid', desc: '自上而下金字塔分层' },
  { template: 'list-pyramid-badge-card', name: '金字塔徽章卡', category: 'pyramid', desc: '分层 + 徽章' },
  // —— 扇形 ——
  { template: 'list-sector-simple', name: '扇形分布', category: 'sector', desc: '环形扇区呈现占比' },
  // —— 瀑布 ——
  { template: 'list-waterfall-compact-card', name: '瀑布流紧凑卡', category: 'waterfall', desc: '错落瀑布排布' },
  { template: 'list-waterfall-badge-card', name: '瀑布流徽章卡', category: 'waterfall', desc: '瀑布 + 徽章' },
  // —— 流程 ——
  { template: 'sequence-timeline-simple', name: '时间线', category: 'sequence', desc: '按时间顺序串联' },
  { template: 'sequence-steps-simple', name: '步骤条', category: 'sequence', desc: '横向步骤流程' },
  { template: 'sequence-snake-steps-simple', name: '蛇形步骤', category: 'sequence', desc: '上下交错的步骤' },
  { template: 'sequence-roadmap-vertical-simple', name: '纵向路线图', category: 'sequence', desc: '竖向里程碑路线' },
  { template: 'sequence-funnel-simple', name: '漏斗', category: 'sequence', desc: '逐级收窄的漏斗' },
  { template: 'sequence-circular-simple', name: '环形流程', category: 'sequence', desc: '闭环环形步骤' },
  { template: 'sequence-ascending-stairs-3d-simple', name: '上升阶梯', category: 'sequence', desc: '逐级上升的阶梯' },
  { template: 'sequence-pyramid-simple', name: '金字塔阶梯', category: 'sequence', desc: '金字塔式递进' },
  // —— 对比（分组数据：每个分组 = 一根节点 + children） ——
  { template: 'compare-swot', name: 'SWOT 四象限', category: 'compare', dataShape: 'grouped', desc: '四分组对照（如 S/W/O/T）' },
  { template: 'compare-binary-horizontal-simple-vs', name: '左右对比 VS', category: 'compare', dataShape: 'grouped', desc: '两组要点左右对照（VS 分隔）' },
  { template: 'compare-binary-horizontal-badge-card-vs', name: '左右对比 VS · 徽章卡', category: 'compare', dataShape: 'grouped', desc: '两组要点对照，卡片带徽章' },
  { template: 'compare-hierarchy-left-right-circle-node-plain-text', name: '层级左右对比', category: 'compare', dataShape: 'grouped', desc: '左右两棵层级树对照' },
  // —— 关系（扁平数据：首项为中心 / 沿圆周排布） ——
  { template: 'relation-network-simple-circle-node', name: '关系网络', category: 'relation', desc: '首项为中心，其余辐射相连' },
  { template: 'relation-circle-icon-badge', name: '环形关系', category: 'relation', desc: '要点沿圆周环绕排布' }
]

/** 默认模板（新增信息图时使用）。 */
export const INFO_DEFAULT_TEMPLATE = 'list-row-simple-horizontal-arrow'

/** 分类列表（用于画廊标签渲染，'all' 在前）。 */
export const INFO_CATEGORIES: (InfographicCategory | 'all')[] = [
  'all',
  ...(Object.keys(INFO_CATEGORY_LABELS).filter((k) => k !== 'all') as InfographicCategory[])
]
