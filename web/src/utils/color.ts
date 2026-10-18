/** #rgb / #rrggbb → [r, g, b]，格式不对时返回 null */
export function parseHex(hex: string): [number, number, number] | null {
  let h = hex.trim().replace(/^#/, '')
  if (h.length === 3) h = h.split('').map((c) => c + c).join('')
  if (!/^[0-9a-f]{6}$/i.test(h)) return null
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}

export function toHex([r, g, b]: [number, number, number]): string {
  const c = (n: number) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, '0')
  return `#${c(r)}${c(g)}${c(b)}`
}

/** 两个颜色按比例混合（ratio 为 b 的占比） */
export function mix(a: string, b: string, ratio: number): string {
  const x = parseHex(a)
  const y = parseHex(b)
  if (!x || !y) return a
  return toHex([0, 1, 2].map((i) => x[i] + (y[i] - x[i]) * ratio) as [number, number, number])
}

/** 相对亮度（0 黑 ~ 1 白） */
export function luminance(hex: string): number {
  const c = parseHex(hex)
  if (!c) return 1
  const [r, g, b] = c.map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** 是否为浅色 */
export function isLight(hex: string): boolean {
  return luminance(hex) > 0.35
}
