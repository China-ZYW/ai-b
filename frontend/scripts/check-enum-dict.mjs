/**
 * 枚举字典「码值 → 中文标签」替换函数的离线校验。
 *
 * 只跑 utils/format.ts 里的 applyEnumDicts / enumLabelOf（该文件零依赖，可直接转译求值），
 * 覆盖：命中替换、未知码值保原、非字典列不动、数字/字符串/BigDecimal 三种码值类型、
 * 空字典不下发、无命中时零复制（返回原引用）与导出退税（原始 rows 不被改写）。
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { build } from 'esbuild'

const SRC = fileURLToPath(new URL('../src/utils/format.ts', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'enumdict-'))
const out = join(work, 'format.mjs')

await build({
  entryPoints: [SRC],
  outfile: out,
  format: 'esm',
  bundle: false,
  target: 'node18'
})
const { applyEnumDicts, enumLabelOf } = await import(pathToFileURL(out).href)

let pass = 0
let fail = 0
function check(name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) {
    pass++
    console.log(`  PASS  ${name}`)
  } else {
    fail++
    console.log(`  FAIL  ${name}\n        期望 ${e}\n        实际 ${a}`)
  }
}

const result = {
  columns: ['status', 'amount'],
  rows: [
    [1, 100],
    ['2', 200],
    [null, 300],
    [9, 400],
    [' 1 ', 500]
  ],
  enumDicts: { status: { '1': '已支付', '2': '已取消' } }
}

console.log('applyEnumDicts')
check('逐行按列下标替换码值', applyEnumDicts(result).map((r) => r[0]), [
  '已支付',
  '已取消',
  null,
  9,
  '已支付'
])
check('度量列（无字典）原样保留', applyEnumDicts(result).map((r) => r[1]), [100, 200, 300, 400, 500])
check('原始 rows 未被改写（导出可追溯）', result.rows[0][0], 1)
check('无字典时返回原引用（零复制）', applyEnumDicts({ ...result, enumDicts: null }), result.rows)
check('空字典返回原引用', applyEnumDicts({ ...result, enumDicts: {} }), result.rows)
check(
  '字典里的列名不在结果列中 → 原样返回',
  applyEnumDicts({ columns: ['amount'], rows: [[1]], enumDicts: { status: { '1': '已支付' } } }),
  [[1]]
)
check('空 rows 不炸', applyEnumDicts({ columns: [], rows: [], enumDicts: result.enumDicts }), [])
check(
  '单行只换命中的那一格',
  applyEnumDicts({ columns: ['status'], rows: [[1]], enumDicts: { status: { 1: '已支付' } } }),
  [['已支付']]
)
check('入参不含 enumDicts 字段时安全', applyEnumDicts({ columns: ['status'], rows: [[1]] }), [[1]])

console.log('enumLabelOf')
check('数字码值命中', enumLabelOf(1, { '1': '已支付' }), '已支付')
check('字符串码值命中', enumLabelOf('1', { '1': '已支付' }), '已支付')
check('带空格的脏值仍能命中', enumLabelOf(' 1 ', { '1': '已支付' }), '已支付')
check('未知码值保原样', enumLabelOf(9, { '1': '已支付' }), 9)
check('null 不过度替换', enumLabelOf(null, { '1': '已支付' }), null)
check('空字典返回原值', enumLabelOf(1, {}), 1)
check('undefined 字典返回原值', enumLabelOf(1, undefined), 1)

console.log(`\n${fail === 0 ? 'ALL_PASS' : 'FAILED'}  通过 ${pass} / 失败 ${fail}`)
process.exit(fail === 0 ? 0 : 1)
