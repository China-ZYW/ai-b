/**
 * 轻量 SQL 格式化（「格式化」按钮的实现）。
 *
 * <b>为什么不用 sql-formatter 依赖</b>：引入 ~200KB 只为更好看的缩进；本编辑器场景
 * （分析师手写单条只读 SELECT）用轻量规则已足够：主子句换行 + 括号深度缩进 + SELECT 列表逗号断行。
 *
 * <b>安全红线</b>：字符串字面量、反引号标识符、注释先替换为占位符保护，格式化全程不触碰其内容，
 * 最后原样还原——只增加空白与统一关键字大小写，任何情况下不改变 SQL 语义。
 *
 * 规则：
 * - 主子句（SELECT / FROM / WHERE / GROUP BY / ORDER BY / HAVING / LIMIT / OFFSET / *JOIN / UNION / VALUES）
 *   一律另起一行，按括号深度缩进（子查询整体多一层缩进）；
 * - SELECT 列表在「与 SELECT 同括号深度」的逗号后断行，函数参数里的逗号不受影响；
 * - 关键字统一大写；AND/OR/CASE 表达式保持行内，不做碎化换行。
 */

const KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'GROUP', 'ORDER', 'BY', 'HAVING', 'LIMIT', 'OFFSET',
  'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'OUTER', 'CROSS', 'ON', 'USING',
  'UNION', 'ALL', 'VALUES', 'WITH', 'DISTINCT', 'AS', 'AND', 'OR', 'NOT', 'IN',
  'EXISTS', 'BETWEEN', 'LIKE', 'IS', 'NULL', 'ASC', 'DESC',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'CAST', 'COALESCE', 'NULLIF', 'IFNULL', 'CONCAT'
])

const CLAUSE_SIMPLE = new Set(['SELECT', 'FROM', 'WHERE', 'HAVING', 'LIMIT', 'OFFSET', 'VALUES'])
const JOIN_START = new Set(['JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'CROSS'])

export function formatSql(input: string): string {
  if (!input || !input.trim()) return input

  // 1) 保护字面量与注释：字符串 / 反引号标识符 / 行注释 / 块注释 → 占位符（注释另行标记，落行后立即断行）
  const store: string[] = []
  const commentIdx = new Set<number>()
  const src = input.replace(
    /'(?:[^']|'')*'|`[^`]*`|--[^\n]*|\/\*[\s\S]*?\*\//g,
    (m) => {
      const idx = store.push(m) - 1
      if (/^\s*(--|\/\*)/.test(m)) commentIdx.add(idx)
      return `\u0000${idx}\u0000`
    }
  )

  // 2) 切词：先给括号/逗号两侧补空格，再按空白切。
  //    （不用零宽断言 split：JS split 在零宽匹配后不消费相邻空白，token 会带前导空格。
  //     占位符是 \u0000N\u0000，不含括号/逗号，此替换不会触碰被保护的字面量。）
  const tokens = src
    .replace(/[(),]/g, ' $& ')
    .split(/\s+/)
    .filter(Boolean)

  // 3) 重排：主子句换行 + 深度缩进 + SELECT 列表逗号断行
  let depth = 0
  let selectDepth = -1 // 最近一个 SELECT 所在括号深度；同深度出现 FROM 后失效
  let out = ''
  let line = ''
  let indent = 0

  const flush = () => {
    const t = line.trim()
    if (t) out += '  '.repeat(Math.max(0, indent)) + t + '\n'
    line = ''
  }

  for (let i = 0; i < tokens.length; i++) {
    const raw = tokens[i]

    if (raw === '(') {
      // 子句关键字后括号留空格（FROM ( ... )），函数名后不留（SUM( ... )）
      const prevWord = line.trimEnd().match(/([A-Za-z_]+)$/)
      const isClause = prevWord ? CLAUSE_SIMPLE.has(prevWord[1].toUpperCase()) : false
      line = line.trimEnd() + (isClause ? ' (' : '(')
      depth++
      continue
    }
    if (raw === ')') {
      depth--
      line = line.trimEnd() + ') '
      continue
    }
    if (raw === ',') {
      line = line.trimEnd() + ', '
      // 只在 SELECT 列表本身（与 SELECT 同深度）的逗号后断行；函数参数更深层，不受影响
      if (selectDepth >= 0 && depth === selectDepth) {
        flush()
        indent = depth + 1
      }
      continue
    }
    if (!/^[A-Za-z_]\w*$/.test(raw)) {
      // 占位符（字面量/注释/反引号标识符）或其它符号：原样落行；行尾注释后立即断行
      line += raw + ' '
      const ph = raw.match(/^\u0000(\d+)\u0000$/)
      if (ph && commentIdx.has(Number(ph[1]))) flush()
      continue
    }

    let up = raw.toUpperCase()

    // 复合子句：GROUP / ORDER + BY
    if ((up === 'GROUP' || up === 'ORDER') && tokens[i + 1]?.toUpperCase() === 'BY') {
      up = `${up} BY`
      i++
    }
    // JOIN 家族：[LEFT|RIGHT|FULL|INNER|CROSS] [OUTER] JOIN；拼不出 *JOIN 不当子句
    if (JOIN_START.has(up)) {
      const parts = [up]
      if (up !== 'JOIN' && tokens[i + 1]?.toUpperCase() === 'OUTER') {
        parts.push('OUTER')
        i++
      }
      if (tokens[i + 1]?.toUpperCase() === 'JOIN') {
        parts.push('JOIN')
        i++
      }
      if (parts[parts.length - 1] === 'JOIN') {
        flush()
        indent = depth
        line = parts.join(' ') + ' '
        continue
      }
      up = parts.join(' ')
    }
    // UNION [ALL]
    if (up === 'UNION' && tokens[i + 1]?.toUpperCase() === 'ALL') {
      up = 'UNION ALL'
      i++
    }

    // 主子句起点：换行重起
    if (
      CLAUSE_SIMPLE.has(up) ||
      up === 'GROUP BY' ||
      up === 'ORDER BY' ||
      up === 'UNION' ||
      up === 'UNION ALL'
    ) {
      flush()
      indent = depth
      line = up + ' '
      if (up === 'SELECT') selectDepth = depth
      else if (up === 'FROM' && depth === selectDepth) selectDepth = -1
      continue
    }

    if (KEYWORDS.has(up)) {
      line += up + ' '
      continue
    }
    line += raw + ' '
  }
  flush()

  // 4) 还原被保护的字面量
  return out.replace(/\u0000(\d+)\u0000/g, (_, n) => store[Number(n)]).trimEnd()
}
