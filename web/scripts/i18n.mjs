// 界面翻译检查：npm run i18n [-- --write] [-- --lint]
//
// - 从 src 中提取所有 t('原文') 的原文
// - 报告 en.json / zh-TW.json 缺少的翻译、多余的条目、{0} 占位符不一致的条目
// - --write：用 OpenCC（简体 → 台湾正体，含常用词转换）补上 zh-TW.json 缺少的条目，删除两个文件中不再使用的条目，
//            并把缺少英文翻译的原文写到 scripts/i18n-todo.json
// - --lint：列出没有包在 t() 里的中文（可能漏翻译；注释、正则等会有误报）
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import * as OpenCC from 'opencc-js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = path.join(root, 'src')
const i18nDir = path.join(srcDir, 'i18n')
const args = new Set(process.argv.slice(2))
const CJK = /[㐀-鿿]/

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) return walk(p)
    return /\.(vue|ts)$/.test(e.name) && !e.name.endsWith('.d.ts') ? [p] : []
  })
}

const files = walk(srcDir).filter((f) => !f.startsWith(i18nDir))
const scanner = ts.createScanner(ts.ScriptTarget.Latest, false)

/** 提取文件中 t('...') 的原文（按 JS 字符串规则解析转义） */
function extract(code) {
  const keys = []
  const re = /(?<![\w.$])t\(\s*(['"`])/g
  let m
  while ((m = re.exec(code))) {
    const start = m.index + m[0].length - 1
    scanner.setText(code, start)
    const tok = scanner.scan()
    if (tok === ts.SyntaxKind.StringLiteral || tok === ts.SyntaxKind.NoSubstitutionTemplateLiteral) keys.push(scanner.getTokenValue())
  }
  return keys
}

const used = new Map()
for (const f of files) {
  for (const k of extract(fs.readFileSync(f, 'utf8'))) {
    if (!used.has(k)) used.set(k, path.relative(root, f))
  }
}

const load = (name) => {
  try {
    return JSON.parse(fs.readFileSync(path.join(i18nDir, name), 'utf8'))
  } catch {
    return {}
  }
}
const en = load('en.json')
const tw = load('zh-TW.json')
const placeholders = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((x) => x[1]).sort().join(',')

let problems = 0
function report(title, list) {
  if (!list.length) return
  problems += list.length
  console.log(`\n${title}（${list.length}）`)
  for (const l of list.slice(0, 80)) console.log('  ' + l)
  if (list.length > 80) console.log(`  … 还有 ${list.length - 80} 条`)
}

const keys = [...used.keys()]
const missingEn = keys.filter((k) => !(k in en))
const missingTw = keys.filter((k) => !(k in tw))
report('缺少英文翻译', missingEn.map((k) => `${JSON.stringify(k)}  ← ${used.get(k)}`))
if (!args.has('--write')) report('缺少繁体翻译（--write 自动生成）', missingTw.map((k) => JSON.stringify(k)))
for (const [name, dict] of [['en.json', en], ['zh-TW.json', tw]]) {
  report(`${name} 占位符和原文不一致`, keys.filter((k) => k in dict && placeholders(k) !== placeholders(dict[k])).map((k) => `${JSON.stringify(k)} → ${JSON.stringify(dict[k])}`))
  const unused = Object.keys(dict).filter((k) => !used.has(k))
  if (!args.has('--write')) report(`${name} 中不再使用的条目（--write 删除）`, unused.map((k) => JSON.stringify(k)))
}

if (args.has('--write')) {
  const opencc = OpenCC.Converter({ from: 'cn', to: 'twp' })
  // OpenCC 之后再按台湾的习惯修正几个词
  const TW_FIX = [
    [/賬號/g, '帳號'],
    [/全屏/g, '全螢幕'],
    [/檢視更多/g, '查看更多'],
    [/(查看更多|展開 \{0\} 條|^)回復/g, '$1回覆'],
    [/後臺/g, '後台'],
  ]
  const toTw = (s) => TW_FIX.reduce((r, [re, to]) => r.replace(re, to), opencc(s))
  const sortByUse = (dict) => Object.fromEntries(keys.filter((k) => k in dict).map((k) => [k, dict[k]]))
  for (const k of missingTw) tw[k] = toTw(k)
  fs.writeFileSync(path.join(i18nDir, 'zh-TW.json'), JSON.stringify(sortByUse(tw), null, 2) + '\n')
  fs.writeFileSync(path.join(i18nDir, 'en.json'), JSON.stringify(sortByUse(en), null, 2) + '\n')
  const todo = path.join(root, 'scripts', 'i18n-todo.json')
  if (missingEn.length) fs.writeFileSync(todo, JSON.stringify(Object.fromEntries(missingEn.map((k) => [k, ''])), null, 2) + '\n')
  else if (fs.existsSync(todo)) fs.unlinkSync(todo)
  console.log(`\n已写入：zh-TW.json 新增 ${missingTw.length} 条${missingEn.length ? `；缺少英文的 ${missingEn.length} 条已写到 scripts/i18n-todo.json` : ''}`)
}

if (args.has('--lint')) {
  const lines = []
  for (const f of files) {
    if (/pageShim\.ts$/.test(f)) continue
    let code = fs.readFileSync(f, 'utf8')
    // 去掉注释和已翻译的文字
    code = code.replace(/<!--[\s\S]*?-->/g, (s) => s.replace(/[^\n]/g, ' ')).replace(/\/\*[\s\S]*?\*\//g, (s) => s.replace(/[^\n]/g, ' '))
    code.split('\n').forEach((line, i) => {
      const rest = line.replace(/(?<![:\w])\/\/.*$/, '').replace(/(?<![\w.$])t\(\s*(['"`])(?:\\.|(?!\1).)*\1/g, '')
      if (CJK.test(rest)) lines.push(`${path.relative(root, f)}:${i + 1}  ${line.trim().slice(0, 120)}`)
    })
  }
  report('可能没有翻译的中文', lines)
}

console.log(problems ? `\n共 ${problems} 处需要处理` : '\n翻译完整')
process.exitCode = problems && !args.has('--write') ? 1 : 0
