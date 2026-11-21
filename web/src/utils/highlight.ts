/**
 * 高亮规则：阅读时把正文中匹配的文字（人名、关键词、对话等）用指定的样式显示。
 *
 * 规则和样式的格式与阅读 App（LegadoTeam/legado 的 HighlightRule / HighlightStyle）相同，导出的 JSON 可以互相导入：
 * - 样式 style 是 JSON 字符串，颜色是 ARGB 整数（0 表示不使用）
 * - 规则按列表顺序（order）依次叠加：同一项样式（如背景色）多条规则都设置时，排在后面的生效；粗体、斜体等开关任一条开启即开启
 * - 作用范围 scope 包含书名或书源地址即生效，留空为全部
 *
 * 只在显示时给匹配的文字套一层 <span>，不改动正文内容：书签、书内搜索、朗读、复制都不受影响。
 * 规则保存在账号的偏好（prefs 的 highlightRules）里，备份时导出为 highlightRule.json
 */

export type FillShape = 'RECTANGLE' | 'ROUNDED' | 'MARKER' | 'HALF' | 'BASELINE' | 'PILL'
export type LineKind = 'SOLID' | 'WAVY' | 'DASHED' | 'DOTTED' | 'DOUBLE'

export interface HighlightStyle {
  fill?: number
  fillShape?: FillShape | null
  pillPaddingScale?: number | null
  textColor?: number
  bold?: boolean
  italic?: boolean
  underline?: { kind?: LineKind; color?: number; width?: number; distance?: number } | null
  strike?: { color?: number } | null
  box?: { color?: number } | null
  emphasis?: { color?: number } | null
  shadow?: { radius?: number; dx?: number; dy?: number; color?: number } | null
  letterSpacing?: number | null
  /** 网页版不支持的项（字体、字号、左右边距等）原样保留，导出给 App 时不丢失 */
  [k: string]: unknown
}

export interface HighlightRule {
  uuid: string
  name: string
  pattern: string
  isRegex: boolean
  scope: string | null
  isEnabled: boolean
  /** HighlightStyle 的 JSON 字符串 */
  style: string
  order: number
  timeoutMillisecond: number
  group: string | null
  applyToTitle: boolean
  applyToBody: boolean
}

/** 正文的一小段：style 为空是普通文字 */
export interface HighlightPart {
  text: string
  style?: Record<string, string>
  /** 最上层的手动标注（点击时打开它的操作菜单） */
  mark?: number
}

export function uuid(): string {
  // crypto.randomUUID 只在 https / localhost 下可用，局域网 http 访问时自己生成
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto && window.isSecureContext) return crypto.randomUUID()
  const b = new Uint8Array(16)
  crypto.getRandomValues(b)
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// ---------------- 颜色：App 用 ARGB 整数 ----------------

export function argbToCss(v: number | undefined | null): string {
  if (!v) return ''
  const n = v >>> 0
  const a = ((n >>> 24) & 255) / 255
  return `rgba(${(n >>> 16) & 255}, ${(n >>> 8) & 255}, ${n & 255}, ${+a.toFixed(3)})`
}

/** 颜色选择器的值（#RRGGBB、#RRGGBBAA、rgb()/rgba()）转为 ARGB 整数（有符号，与 App 的 Int 相同） */
export function cssToArgb(s: string | null | undefined): number {
  if (!s) return 0
  let r = 0, g = 0, b = 0, a = 255
  const hex = s.trim().match(/^#([0-9a-f]{3,8})$/i)
  if (hex) {
    let h = hex[1]
    if (h.length <= 4) h = [...h].map((c) => c + c).join('')
    r = parseInt(h.slice(0, 2), 16)
    g = parseInt(h.slice(2, 4), 16)
    b = parseInt(h.slice(4, 6), 16)
    if (h.length === 8) a = parseInt(h.slice(6, 8), 16)
  } else {
    const m = s.match(/rgba?\(([^)]+)\)/i)
    if (!m) return 0
    const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number)
    ;[r, g, b] = p
    if (p.length > 3) a = Math.round(Math.min(1, Math.max(0, p[3])) * 255)
  }
  return ((a << 24) | (r << 16) | (g << 8) | b) | 0
}

/** ARGB 转为颜色选择器使用的 #RRGGBBAA */
export function argbToHex(v: number | undefined | null): string | null {
  if (!v) return null
  const n = v >>> 0
  const hx = (x: number) => x.toString(16).padStart(2, '0')
  return `#${hx((n >>> 16) & 255)}${hx((n >>> 8) & 255)}${hx(n & 255)}${hx((n >>> 24) & 255)}`
}

// ---------------- 样式 ----------------

export function parseStyle(s: string | null | undefined): HighlightStyle {
  if (!s) return {}
  try {
    const v = JSON.parse(s)
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {}
  } catch {
    return {}
  }
}

/** 去掉未使用的项，保持 JSON 简短（App 读取时缺少的项用默认值） */
export function stringifyStyle(st: HighlightStyle): string {
  const out: HighlightStyle = {}
  for (const [k, v] of Object.entries(st)) {
    if (v === null || v === undefined || v === false || v === 0 || v === '') continue
    out[k] = v
  }
  if (!out.fill) {
    delete out.fillShape
    delete out.pillPaddingScale
  }
  return JSON.stringify(out)
}

export function isEmptyStyle(st: HighlightStyle): boolean {
  return !st.fill && !st.textColor && !st.bold && !st.italic && !st.underline && !st.strike && !st.box &&
    !st.emphasis && !st.shadow && !st.letterSpacing
}

/** 与 App 的 HighlightStyle.merge 相同：后面的规则设置了的项覆盖前面的，开关取“或” */
export function mergeStyle(base: HighlightStyle, o: HighlightStyle): HighlightStyle {
  return {
    ...base,
    fill: o.fill || base.fill,
    fillShape: o.fill ? o.fillShape : base.fillShape,
    pillPaddingScale: o.fill ? o.pillPaddingScale : base.pillPaddingScale,
    textColor: o.textColor || base.textColor,
    bold: !!(o.bold || base.bold),
    italic: !!(o.italic || base.italic),
    underline: o.underline ?? base.underline,
    strike: o.strike ?? base.strike,
    box: o.box ?? base.box,
    emphasis: o.emphasis ?? base.emphasis,
    shadow: o.shadow ?? base.shadow,
    letterSpacing: o.letterSpacing ?? base.letterSpacing,
  }
}

const clamp = (v: number | null | undefined, min: number, max: number, def: number) =>
  typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : def

/** 样式转为 CSS。尺寸（App 里是 dp）按 px 处理 */
export function styleToCss(st: HighlightStyle): Record<string, string> {
  const css: Record<string, string> = {}
  const shadows: string[] = []
  const fill = argbToCss(st.fill)
  if (fill) {
    switch (st.fillShape ?? 'RECTANGLE') {
      case 'ROUNDED':
        css.backgroundColor = fill
        css.borderRadius = '0.25em'
        break
      case 'MARKER':
        // 马克笔：稍微偏下、两端斜一点
        css.backgroundImage = `linear-gradient(transparent 22%, ${fill} 22%, ${fill} 92%, transparent 92%)`
        css.borderRadius = '0.5em 0.15em'
        break
      case 'HALF':
        css.backgroundImage = `linear-gradient(transparent 50%, ${fill} 50%)`
        break
      case 'BASELINE':
        css.backgroundImage = `linear-gradient(transparent 80%, ${fill} 80%)`
        break
      case 'PILL': {
        // 用阴影扩出两端，不改变文字排版
        const pad = (0.22 * clamp(st.pillPaddingScale, 0.25, 2, 1)).toFixed(3)
        css.backgroundColor = fill
        css.borderRadius = '999px'
        shadows.push(`0 0 0 ${pad}em ${fill}`)
        break
      }
      default:
        css.backgroundColor = fill
    }
  }
  const color = argbToCss(st.textColor)
  if (color) css.color = color
  if (st.bold) css.fontWeight = '700'
  if (st.italic) css.fontStyle = 'italic'
  const lines: string[] = []
  if (st.underline) {
    lines.push('underline')
    const u = st.underline
    css.textDecorationStyle = (u.kind ?? 'SOLID').toLowerCase()
    const uc = argbToCss(u.color)
    if (uc) css.textDecorationColor = uc
    css.textDecorationThickness = `${clamp(u.width, 0, 10, 1) || 1}px`
    css.textUnderlineOffset = u.distance ? `${clamp(u.distance, 0, 30, 0)}px` : '0.2em'
  }
  if (st.strike) {
    lines.push('line-through')
    // 一个元素只能有一种装饰线颜色：同时有下划线时用下划线的颜色
    if (!st.underline) {
      const sc = argbToCss(st.strike.color)
      if (sc) css.textDecorationColor = sc
    }
  }
  if (lines.length) css.textDecorationLine = lines.join(' ')
  if (st.box) shadows.push(`inset 0 0 0 1px ${argbToCss(st.box.color) || 'currentColor'}`)
  if (shadows.length) css.boxShadow = shadows.join(', ')
  if (st.emphasis) {
    css.textEmphasis = `filled dot ${argbToCss(st.emphasis.color) || 'currentColor'}`
    css.textEmphasisPosition = 'under right'
  }
  if (st.shadow) {
    const s = st.shadow
    css.textShadow = `${clamp(s.dx, -10, 10, 2)}px ${clamp(s.dy, -10, 10, 2)}px ${clamp(s.radius, 0, 10, 3)}px ${argbToCss(s.color ?? 0x80000000 | 0) || 'rgba(0,0,0,0.5)'}`
  }
  if (st.letterSpacing) css.letterSpacing = `${clamp(st.letterSpacing, -0.5, 1, 0)}em`
  return css
}

/** 与 App 的 HighlightStyles.presets 相同 */
export const presetStyles: HighlightStyle[] = [
  { fill: 0x80fff176 | 0 },
  { fill: 0x804fc3f7 | 0, fillShape: 'ROUNDED' },
  { fill: 0x8069f0ae | 0, fillShape: 'HALF' },
  { underline: { kind: 'WAVY', color: 0xffe53935 | 0 } },
  { underline: { kind: 'SOLID', color: 0xff1e88e5 | 0 }, bold: true },
  { strike: { color: 0xff9e9e9e | 0 } },
  { emphasis: { color: 0xffe53935 | 0 } },
]

/** 与 App 的 HighlightColors 相同 */
export const bgSwatches = [0x80fff176, 0x80aed581, 0x804fc3f7, 0x80f06292, 0x80ffb74d].map((c) => argbToHex(c | 0)!)
export const textSwatches = [0xffd32f2f, 0xff1976d2, 0xff388e3c, 0xfff57c00, 0xff7b1fa2].map((c) => argbToHex(c | 0)!)

// ---------------- 规则 ----------------

export const blankHighlightRule = (): HighlightRule => ({
  uuid: uuid(),
  name: '',
  pattern: '',
  isRegex: false,
  scope: null,
  isEnabled: true,
  style: stringifyStyle(presetStyles[0]),
  order: 0,
  timeoutMillisecond: 3000,
  group: null,
  applyToTitle: false,
  applyToBody: true,
})

/**
 * 整理导入或从服务器读到的规则：接受 App 导出的 {"type":"highlightRule","rules":[...]} 或规则数组，
 * 缺少的字段补默认值，没有内容的丢弃，uuid 无效或重复时重新生成
 */
export function normalizeHighlightRules(raw: unknown): HighlightRule[] {
  if (raw && typeof raw === 'object' && !Array.isArray(raw) && Array.isArray((raw as { rules?: unknown }).rules)) {
    raw = (raw as { rules: unknown[] }).rules
  }
  if (!Array.isArray(raw)) raw = raw && typeof raw === 'object' ? [raw] : []
  const str = (v: unknown) => (typeof v === 'string' ? v : '')
  const seen = new Set<string>()
  const out: HighlightRule[] = []
  for (const r of raw as Record<string, unknown>[]) {
    if (!r || typeof r !== 'object' || !str(r.pattern)) continue
    let id = str(r.uuid).toLowerCase()
    if (!UUID_RE.test(id) || seen.has(id)) id = uuid()
    seen.add(id)
    out.push({
      uuid: id,
      name: str(r.name),
      pattern: str(r.pattern),
      isRegex: r.isRegex === true,
      scope: str(r.scope) || null,
      isEnabled: r.isEnabled !== false,
      style: typeof r.style === 'string' ? r.style : r.style && typeof r.style === 'object' ? JSON.stringify(r.style) : '{}',
      order: typeof r.order === 'number' && Number.isFinite(r.order) ? r.order : out.length,
      timeoutMillisecond: typeof r.timeoutMillisecond === 'number' && r.timeoutMillisecond > 0 ? r.timeoutMillisecond : 3000,
      group: str(r.group).trim() || null,
      applyToTitle: r.applyToTitle === true,
      applyToBody: r.applyToBody !== false,
    })
  }
  return out
}

/** 按顺序排列并把 order 重新编号为 0、1、2…（与 App 导入、移动规则后的处理相同） */
export function renumber(rules: HighlightRule[]): HighlightRule[] {
  return [...rules].sort((a, b) => a.order - b.order).map((r, i) => (r.order === i ? r : { ...r, order: i }))
}

/** 导入：uuid 相同的覆盖原规则（位置不变），其余追加到后面（与 App 的 mergeImportedHighlightRules 相同） */
export function mergeImported(current: HighlightRule[], imported: HighlightRule[]): HighlightRule[] {
  const byUuid = new Map(imported.map((r) => [r.uuid, r]))
  const existing = new Set(current.map((r) => r.uuid))
  const merged = [...renumber(current).map((r) => byUuid.get(r.uuid) ?? r), ...imported.filter((r) => !existing.has(r.uuid))]
  return merged.map((r, i) => ({ ...r, order: i }))
}

/** App 导出的格式 */
export function exportJson(rules: HighlightRule[]): string {
  return JSON.stringify({ type: 'highlightRule', rules: renumber(rules) }, null, 2)
}

/**
 * App 的规则是 Java 正则。开头的内联标志（如 (?i)）JavaScript 的正则不支持，转成标志；
 * 其他 JavaScript 不支持的写法（占有量词等）编译失败时这条规则不生效
 */
export function compileRule(r: Pick<HighlightRule, 'pattern' | 'isRegex'>): RegExp {
  if (!r.isRegex) return new RegExp(r.pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')
  let src = r.pattern
  let flags = 'g'
  const m = src.match(/^\(\?([imsux]+)\)/)
  if (m) {
    src = src.slice(m[0].length)
    for (const f of m[1]) if ('ims'.includes(f) && !flags.includes(f)) flags += f
  }
  try {
    return new RegExp(src, flags)
  } catch (e) {
    // \p{...} 等需要 u 标志
    try {
      return new RegExp(src, flags + 'u')
    } catch {
      throw e
    }
  }
}

/** 检查规则能否使用：'empty' 没有内容；'matchesEmpty' 正则能匹配空字符串；其他为正则的错误信息 */
export function highlightRuleError(r: Pick<HighlightRule, 'pattern' | 'isRegex'>): string | null {
  if (!r.pattern) return 'empty'
  if (!r.isRegex) return null
  try {
    const re = compileRule(r)
    re.lastIndex = 0
    if (re.test('')) return 'matchesEmpty'
  } catch (e: any) {
    return String(e?.message ?? e)
  }
  return null
}

/** 每条规则在一段文字中最多处理这么多处匹配，防止规则写得太宽（如单个字）时卡住页面 */
const MAX_MATCHES = 2000
/** 同时生效的规则数上限（实际很少有这么多条同时作用于一本书） */
const MAX_RULES = 64

interface Compiled {
  re: RegExp
  style: HighlightStyle
  title: boolean
  body: boolean
}

/**
 * 手动标注在一段文字中的位置（start、end 相对于这段文字），见 layoutMarks。
 * 标注叠加在高亮规则上面（与 App 相同：先规则，后标注）
 */
export interface Mark {
  id: number
  start: number
  end: number
  style: HighlightStyle
}

/**
 * isTitle：是否是章节标题（规则可以分别设置是否作用于标题、正文）；marks：这段文字里的手动标注。
 * 没有可用的规则时 rules 为空数组
 */
export interface Highlighter {
  (text: string, isTitle?: boolean, marks?: Mark[]): HighlightPart[] | null
  rules: Compiled[]
}

/**
 * 按书生成高亮函数：只用启用的、作用范围包含这本书的规则。
 * 没有手动标注时结果按段落文字缓存（翻页、滚动时同一段会反复渲染）
 */
export function createHighlighter(rules: HighlightRule[], book: { name?: string; origin?: string } | null): Highlighter {
  const name = book?.name ?? ''
  const origin = book?.origin ?? ''
  const inScope = (scope: string | null) => !scope || (!!name && scope.includes(name)) || (!!origin && scope.includes(origin))
  const compiled: Compiled[] = []
  for (const r of renumber(rules)) {
    if (!r.isEnabled || !r.pattern || !inScope(r.scope) || (!r.applyToTitle && !r.applyToBody)) continue
    const style = parseStyle(r.style)
    if (isEmptyStyle(style) || highlightRuleError(r)) continue
    compiled.push({ re: compileRule(r), style, title: r.applyToTitle, body: r.applyToBody })
    if (compiled.length >= MAX_RULES) break
  }

  const cache = new Map<string, HighlightPart[] | null>()
  const fn = ((text: string, isTitle = false, marks?: Mark[]) => {
    if (!text) return null
    const rs = compiled.filter((c) => (isTitle ? c.title : c.body))
    if (marks?.length) return split(text, rs, marks)
    if (!rs.length) return null
    const key = (isTitle ? 'T' : 'B') + text
    const hit = cache.get(key)
    if (hit !== undefined) return hit
    const parts = split(text, rs, [])
    if (cache.size > 3000) cache.clear()
    cache.set(key, parts)
    return parts
  }) as Highlighter
  fn.rules = compiled
  return fn
}

/** 不使用高亮规则（已关闭）时，只显示手动标注 */
export const marksOnly: Highlighter = createHighlighter([], null)

/**
 * 匹配的文字可以重叠（如对话里的人名）：样式按规则顺序叠加（见 mergeStyle），手动标注在最上面。
 * 做法是给每个字记下作用在它上面的层（规则、标注的序号），再把样式相同的相邻文字合成一段
 */
function split(text: string, rules: Compiled[], marks: Mark[]): HighlightPart[] | null {
  if (!rules.length && !marks.length) return null
  const layers: string[] = new Array(text.length).fill('')
  let any = false
  rules.forEach(({ re }, i) => {
    re.lastIndex = 0
    let n = 0
    let m: RegExpExecArray | null
    while ((m = re.exec(text)) && n++ < MAX_MATCHES) {
      if (!m[0]) {
        re.lastIndex++
        continue
      }
      for (let k = m.index; k < m.index + m[0].length; k++) layers[k] += ',' + i
      any = true
    }
  })
  marks.forEach((mk, j) => {
    const end = Math.min(text.length, mk.end)
    for (let k = Math.max(0, mk.start); k < end; k++) {
      layers[k] += ',' + (rules.length + j)
      any = true
    }
  })
  if (!any) return null
  const cache = new Map<string, { style?: Record<string, string>; mark?: number }>()
  const resolve = (key: string) => {
    let r = cache.get(key)
    if (!r) {
      if (!key) r = {}
      else {
        let st: HighlightStyle = {}
        let mark: number | undefined
        for (const s of key.slice(1).split(',')) {
          const i = Number(s)
          if (i < rules.length) st = mergeStyle(st, rules[i].style)
          else {
            const mk = marks[i - rules.length]
            st = mergeStyle(st, mk.style)
            mark = mk.id
          }
        }
        r = { style: styleToCss(st), mark }
      }
      cache.set(key, r)
    }
    return r
  }
  const parts: HighlightPart[] = []
  const keys: string[] = []
  let start = 0
  for (let k = 1; k <= text.length; k++) {
    if (k === text.length || layers[k] !== layers[start]) {
      const { style, mark } = resolve(layers[start])
      // 规则组合不同但最终样式相同：与前一段合并，少生成一层 span
      const key = style ? JSON.stringify(style) + '|' + (mark ?? '') : ''
      if (parts.length && keys[keys.length - 1] === key) parts[parts.length - 1].text += text.slice(start, k)
      else {
        parts.push({ text: text.slice(start, k), style, mark })
        keys.push(key)
      }
      start = k
    }
  }
  return parts
}

// ---------------- 手动标注的位置 ----------------

/**
 * 章节正文（标注位置的坐标）：文字段落按顺序拼接，每段后面一个换行；图片段落不计入，章节名不计入。
 * 返回每个文字段落在正文中的起点（图片段落为 -1）和拼接后的正文
 */
export function chapterLayout(paras: { type: string; text?: string }[]): { starts: number[]; text: string } {
  const starts: number[] = []
  let text = ''
  for (const p of paras) {
    if (p.type !== 'text' || typeof p.text !== 'string') {
      starts.push(-1)
      continue
    }
    starts.push(text.length)
    text += p.text + '\n'
  }
  return { starts, text }
}

/**
 * 正文变化（替换规则、换源）后重新定位，参照 App 的 HighlightAnchor.reanchor：
 * 原位置的文字还对得上就不动；否则在正文中找这段文字，找不到时不显示。
 * 与 App 不同的是文字出现多次时取离原位置最近的一处（App 保留原位置）：从 App 导入的标注位置只是大概
 * （两边的正文排版不同），保留原位置会标到别的文字上
 */
export function reanchor(text: string, start: number, end: number, bookText: string): { start: number; end: number } | null {
  if (!bookText) return end > start ? { start, end } : null
  if (!text) return null
  if (start >= 0 && start <= text.length - bookText.length && text.startsWith(bookText, start)) return { start, end: start + bookText.length }
  let best = -1
  for (let i = text.indexOf(bookText); i >= 0; i = text.indexOf(bookText, i + 1)) {
    if (best < 0 || Math.abs(i - start) < Math.abs(best - start)) best = i
  }
  return best < 0 ? null : { start: best, end: best + bookText.length }
}

/** 把一章的标注分到各个段落（位置换成段内偏移），key 为段落序号 */
export function layoutMarks(
  paras: { type: string; text?: string }[],
  highlights: { id: number; chapterPos: number; chapterPosEnd: number; bookText: string; style: string }[],
): Map<number, Mark[]> {
  const out = new Map<number, Mark[]>()
  if (!highlights.length) return out
  const { starts, text } = chapterLayout(paras)
  for (const h of highlights) {
    const a = reanchor(text, h.chapterPos, h.chapterPosEnd, h.bookText)
    if (!a) continue
    const style = parseStyle(h.style)
    starts.forEach((ps, i) => {
      if (ps < 0) return
      const len = (paras[i].text ?? '').length
      const s = Math.max(a.start, ps)
      const e = Math.min(a.end, ps + len)
      if (e <= s) return
      const list = out.get(i) ?? []
      list.push({ id: h.id, start: s - ps, end: e - ps, style })
      out.set(i, list)
    })
  }
  return out
}

/** 段落内的一部分（段内有小图时，文字被分成几块）对应的标注：位置换成相对这块文字 */
export function shiftMarks(marks: Mark[] | undefined, offset: number, length: number): Mark[] | undefined {
  if (!marks?.length) return marks
  const r = marks
    .map((m) => ({ ...m, start: Math.max(0, m.start - offset), end: Math.min(length, m.end - offset) }))
    .filter((m) => m.end > m.start)
  return r.length ? r : undefined
}
