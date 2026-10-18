import { t } from '@/i18n'
/**
 * 阅读页快捷键
 *
 * 按键用字符串表示：修饰键 + 主键，例如 "ArrowRight"、"Ctrl+ArrowRight"、"J"、"Space"。
 * 可打印字符（字母、符号）已经包含了 Shift 的效果，所以不再单独记录 Shift。
 */

export type ReaderAction = 'nextPage' | 'prevPage' | 'nextChapter' | 'prevChapter' | 'toc' | 'fullscreen'

export const readerActions: { key: ReaderAction; label: string }[] = [
  { key: 'nextPage', label: t('下一页') },
  { key: 'prevPage', label: t('上一页') },
  { key: 'nextChapter', label: t('下一章') },
  { key: 'prevChapter', label: t('上一章') },
  { key: 'toc', label: t('打开目录') },
  { key: 'fullscreen', label: t('全屏') },
]

export type KeyBindings = Record<ReaderAction, string[]>

export function defaultKeyBindings(): KeyBindings {
  return {
    nextPage: ['ArrowRight', 'ArrowDown', 'PageDown', 'Space'],
    prevPage: ['ArrowLeft', 'ArrowUp', 'PageUp'],
    nextChapter: [']'],
    prevChapter: ['['],
    toc: [],
    fullscreen: [],
  }
}

/** 补全缺少的动作（旧设置或新增动作时） */
export function normalizeKeyBindings(v: Partial<KeyBindings> | null | undefined): KeyBindings {
  const d = defaultKeyBindings()
  if (!v) return d
  for (const a of readerActions) {
    const list = v[a.key]
    if (Array.isArray(list)) d[a.key] = list.filter((k) => typeof k === 'string')
  }
  return d
}

const MODIFIER_KEYS = new Set(['Control', 'Alt', 'Shift', 'Meta', 'CapsLock', 'OS', 'Hyper', 'Super', 'Fn'])

/** 键盘事件转成按键字符串；单独按修饰键时返回 null */
export function eventToKey(e: KeyboardEvent): string | null {
  if (MODIFIER_KEYS.has(e.key) || e.key === 'Dead' || e.key === 'Unidentified' || e.key === 'Process') return null
  let key = e.key === ' ' ? 'Space' : e.key
  const printable = key.length === 1
  if (printable) key = key.toUpperCase()
  const parts: string[] = []
  if (e.ctrlKey) parts.push('Ctrl')
  if (e.altKey) parts.push('Alt')
  if (e.metaKey) parts.push('Meta')
  if (e.shiftKey && !printable) parts.push('Shift')
  parts.push(key)
  return parts.join('+')
}

const LABELS: Record<string, string> = {
  ArrowRight: '→',
  ArrowLeft: '←',
  ArrowUp: '↑',
  ArrowDown: '↓',
  Space: t('空格'),
  PageDown: 'PgDn',
  PageUp: 'PgUp',
  Escape: 'Esc',
  Enter: t('回车'),
  Backspace: t('退格'),
  Meta: '⌘',
}

export function keyLabel(k: string): string {
  return k
    .split('+')
    .map((p) => LABELS[p] ?? p)
    .join(' + ')
}

/** 查找按键对应的动作 */
export function findAction(bindings: KeyBindings, key: string): ReaderAction | null {
  for (const a of readerActions) if (bindings[a.key]?.includes(key)) return a.key
  return null
}
