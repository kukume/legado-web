/**
 * 正文图片。书源可以在图片地址后附加参数（与 App 相同）：
 *   <img src="图片地址,{"click":"脚本","style":"text"}">
 * - click / js：点击时执行的书源脚本（段评、本章说等）
 * - style：text 表示和文字排在一起的小图标（如段评气泡），FULL 表示占满一行
 */
export interface ContentImage {
  /** 完整地址（含参数），点击时原样交给服务器校验 */
  src: string
  /**
   * 用于显示的地址：data: 图片去掉参数（参数会破坏 base64 数据）；
   * 网络图片保留参数，由服务器图片代理解析（请求头等）
   */
  display: string
  style: 'text' | 'full' | 'default'
  clickable: boolean
}

export type InlinePart = { text: string } | { img: ContentImage }

/**
 * pn：段落序号（正文中非空行从 1 开始，与 App 段评的段落序号一致）；
 * last：是否为该行拆分出的最后一段（段评数字显示在这里）
 */
type ParagraphMeta = { pn: number; last: boolean }

export type Paragraph =
  | ({ type: 'text'; text: string; parts?: InlinePart[] } & ParagraphMeta)
  | ({ type: 'img'; src: string; img: ContentImage } & ParagraphMeta)

/**
 * 与 App 的 AppPattern.imgPattern 一致：src 的参数部分可以包含引号，如
 * <img src="url,{'type':'qtbzs',"click":"fn('a','b')",'style':'FULL'}">（服务器整理正文时已统一成这种格式）
 */
const imgRe = /<img[^>]*src="([^"]*(?:"[^>]+\})?)"[^>]*>/gi
const paramRe = /\s*,\s*(?=\{)/

/** 把书源返回的正文拆分为段落（文字 / 图片）；style 为 text 的图片留在所在段落中 */
export function parseContent(content: string): Paragraph[] {
  const result: Paragraph[] = []
  let pn = 0
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.replace(/^[\s　]+|[\s　]+$/g, '')
    if (!line) continue
    pn++
    const lineStart = result.length
    if (!line.includes('<img')) {
      result.push({ type: 'text', text: stripTags(line), pn, last: true })
      continue
    }
    let parts: InlinePart[] = []
    const flush = () => {
      if (parts.length && parts.some((p) => 'img' in p || p.text.trim())) {
        const text = parts.map((p) => ('text' in p ? p.text : '')).join('').trim()
        const hasImg = parts.some((p) => 'img' in p)
        result.push(hasImg ? { type: 'text', text, parts, pn, last: false } : { type: 'text', text, pn, last: false })
      }
      parts = []
    }
    let last = 0
    let m: RegExpExecArray | null
    imgRe.lastIndex = 0
    while ((m = imgRe.exec(line))) {
      const before = stripTags(line.slice(last, m.index))
      last = m.index + m[0].length
      const img = parseImage(m[1])
      if (img.style === 'text') {
        if (before) parts.push({ text: parts.length ? before : before.trimStart() })
        parts.push({ img })
      } else {
        if (before) parts.push({ text: before })
        flush()
        result.push({ type: 'img', src: img.src, img, pn, last: false })
      }
    }
    const after = stripTags(line.slice(last))
    if (after) parts.push({ text: after.trimEnd() })
    flush()
    if (result.length > lineStart) result[result.length - 1].last = true
  }
  return result
}

export function parseImage(src: string): ContentImage {
  const opt = imageOptions(src)
  const style = String(opt?.style ?? '').toLowerCase()
  const m = paramRe.exec(src)
  const display = m && /^data:/i.test(src) ? src.slice(0, m.index) : src
  return {
    src,
    display,
    style: style === 'text' ? 'text' : style === 'full' ? 'full' : 'default',
    clickable: !!(opt && (nonEmpty(opt.click) || nonEmpty(opt.js))),
  }
}

function nonEmpty(v: unknown) {
  return typeof v === 'string' && v.trim().length > 0
}

/** 图片地址后的参数（宽松 JSON，允许单引号和不带引号的键，与 App 使用的 Gson 宽松模式一致） */
export function imageOptions(src: string): Record<string, unknown> | null {
  const m = paramRe.exec(src)
  if (!m) return null
  try {
    const v = parseLenient(src.slice(m.index + m[0].length))
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function stripTags(s: string) {
  return s.replace(/<[^>]+>/g, '')
}

/** 最小的宽松 JSON 解析：单引号 / 双引号字符串、裸键、数字、true/false/null、对象、数组 */
function parseLenient(input: string): unknown {
  let i = 0
  const ws = () => {
    while (i < input.length && /\s/.test(input[i])) i++
  }
  const str = (q: string) => {
    i++
    let out = ''
    while (i < input.length && input[i] !== q) {
      if (input[i] === '\\' && i + 1 < input.length) {
        const c = input[++i]
        const map: Record<string, string> = { n: '\n', t: '\t', r: '\r', b: '\b', f: '\f' }
        if (c === 'u') {
          out += String.fromCharCode(parseInt(input.slice(i + 1, i + 5), 16))
          i += 4
        } else out += map[c] ?? c
      } else out += input[i]
      i++
    }
    if (input[i] !== q) throw new Error('unterminated string')
    i++
    return out
  }
  const bare = () => {
    const start = i
    while (i < input.length && !/[\s,:{}[\]]/.test(input[i])) i++
    return input.slice(start, i)
  }
  const value = (): unknown => {
    ws()
    const c = input[i]
    if (c === '{') {
      i++
      const obj: Record<string, unknown> = {}
      ws()
      while (input[i] !== '}') {
        ws()
        const key = input[i] === '"' || input[i] === "'" ? str(input[i]) : bare()
        ws()
        if (input[i] !== ':') throw new Error('expected :')
        i++
        obj[key] = value()
        ws()
        if (input[i] === ',') i++
        else if (input[i] !== '}') throw new Error('expected ,')
        ws()
      }
      i++
      return obj
    }
    if (c === '[') {
      i++
      const arr: unknown[] = []
      ws()
      while (input[i] !== ']') {
        arr.push(value())
        ws()
        if (input[i] === ',') i++
        ws()
      }
      i++
      return arr
    }
    if (c === '"' || c === "'") return str(c)
    const word = bare()
    if (word === 'true') return true
    if (word === 'false') return false
    if (word === 'null') return null
    const n = Number(word)
    if (word !== '' && !Number.isNaN(n)) return n
    if (word === '') throw new Error('unexpected token')
    return word
  }
  return value()
}
