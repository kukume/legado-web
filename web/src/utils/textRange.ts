/**
 * 在 root 中查找文字（忽略空白：选中跨段落时文字间有换行），返回对应的 Range。
 * 同样的文字出现多次时，取最接近 pos（章内位置，0..10000）的一处
 */
export function findTextRange(root: HTMLElement, text: string, pos: number): Range | null {
  const nodes: Text[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    // 跳过段评气泡、章节标题等非正文
    acceptNode: (n) => (n.parentElement?.closest('.review-badge, .chapter-title') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  })
  for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n as Text)
  let flat = ''
  const map: { node: Text; offset: number }[] = []
  for (const node of nodes) {
    const t = node.data
    for (let i = 0; i < t.length; i++) {
      if (/\s/.test(t[i])) continue
      flat += t[i]
      map.push({ node, offset: i })
    }
  }
  const needle = text.replace(/\s+/g, '')
  if (!needle) return null
  // 搜索关键字不区分大小写
  const hay = flat.toLowerCase()
  const nearest = (q: string) => {
    const lq = q.toLowerCase()
    let best = -1
    for (let i = hay.indexOf(lq); i >= 0; i = hay.indexOf(lq, i + 1)) {
      if (best < 0 || Math.abs(i / flat.length - pos / 10000) < Math.abs(best / flat.length - pos / 10000)) best = i
    }
    return best
  }
  let len = needle.length
  let at = nearest(needle)
  // 正文被替换规则改过时整段可能匹配不上，退而用开头的一部分定位
  if (at < 0 && needle.length > 12) {
    len = 12
    at = nearest(needle.slice(0, len))
  }
  if (at < 0) return null
  const start = map[at]
  const end = map[at + len - 1]
  const range = document.createRange()
  range.setStart(start.node, start.offset)
  range.setEnd(end.node, end.offset + 1)
  return range
}

let flashTimer: ReturnType<typeof setTimeout> | null = null
let flashedParas: HTMLElement[] = []

/**
 * 短暂高亮一段文字：优先用 CSS Custom Highlight API（不改动正文 DOM，样式为 ::highlight(text-flash)），
 * 不支持的浏览器给所在段落加 text-flash 类
 */
export function flashRange(range: Range, ms = 2200) {
  if (flashTimer) clearTimeout(flashTimer)
  flashedParas.forEach((el) => el.classList.remove('text-flash'))
  flashedParas = []
  const css = CSS as unknown as { highlights?: Map<string, unknown> }
  const HighlightCtor = (window as unknown as { Highlight?: new (r: Range) => unknown }).Highlight
  if (css.highlights && HighlightCtor) {
    css.highlights.set('text-flash', new HighlightCtor(range))
    flashTimer = setTimeout(() => css.highlights?.delete('text-flash'), ms)
    return
  }
  const paras = new Set<HTMLElement>()
  for (const n of [range.startContainer, range.endContainer]) {
    const el = n.parentElement?.closest('.para') as HTMLElement | null
    if (el) paras.add(el)
  }
  flashedParas = [...paras]
  flashedParas.forEach((el) => el.classList.add('text-flash'))
  flashTimer = setTimeout(() => flashedParas.forEach((el) => el.classList.remove('text-flash')), ms)
}
