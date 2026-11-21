import { ref, watch, type Ref } from 'vue'
import { bookHighlightApi } from '@/api'
import type { BookHighlight } from '@/api/types'
import { layoutMarks, parseStyle, presetStyles, stringifyStyle, type HighlightStyle, type Mark } from '@/utils/highlight'
import type { Paragraph } from '@/utils/content'

const LAST_STYLE_KEY = 'legado_highlight_last_style'

/** 上次使用的标注样式（新标注沿用，与 App 相同），没有时用第一个预设 */
export function lastHighlightStyle(): HighlightStyle {
  try {
    const s = parseStyle(localStorage.getItem(LAST_STYLE_KEY))
    if (Object.keys(s).length) return s
  } catch {
    /* ignore */
  }
  return { ...presetStyles[0] }
}

export function saveLastHighlightStyle(s: HighlightStyle) {
  try {
    localStorage.setItem(LAST_STYLE_KEY, stringifyStyle(s))
  } catch {
    /* ignore */
  }
}

/**
 * 阅读页的手动标注：读取本书的标注，按章节、段落分好供正文显示（见 utils/highlight 的 layoutMarks）
 */
export function useBookHighlights(bookId: Ref<number>) {
  const list = ref<BookHighlight[]>([])
  /** 标注有变化时加一（翻页模式重新分页、刷新段落的标注） */
  const version = ref(0)

  async function load() {
    const id = bookId.value
    try {
      const r = await bookHighlightApi.list(id)
      if (id === bookId.value) {
        list.value = r
        version.value++
      }
    } catch {
      // 离线等：不显示标注
    }
  }
  watch(bookId, () => {
    list.value = []
    version.value++
    load()
  }, { immediate: true })

  /** 段落数组 → 按段落分好的标注（同一章的段落数组不变时复用） */
  const cache = new WeakMap<Paragraph[], { version: number; chapter: number; marks: Map<number, Mark[]> }>()
  function marksFor(chapter: number, paras: Paragraph[]): Map<number, Mark[]> | undefined {
    if (!list.value.length || !paras.length) return undefined
    const hit = cache.get(paras)
    if (hit && hit.version === version.value && hit.chapter === chapter) return hit.marks
    const marks = layoutMarks(paras, list.value.filter((h) => h.chapterIndex === chapter))
    cache.set(paras, { version: version.value, chapter, marks })
    return marks
  }

  async function add(h: Parameters<typeof bookHighlightApi.add>[1]) {
    const saved = await bookHighlightApi.add(bookId.value, h)
    list.value = [...list.value, saved]
    version.value++
    return saved
  }

  async function update(id: number, patch: Partial<Pick<BookHighlight, 'style' | 'note'>>) {
    await bookHighlightApi.update(id, patch)
    list.value = list.value.map((h) => (h.id === id ? { ...h, ...patch } : h))
    version.value++
  }

  /** 只改本地显示（调整样式时实时预览），之后再保存 */
  function preview(id: number, style: string) {
    list.value = list.value.map((h) => (h.id === id ? { ...h, style } : h))
    version.value++
  }

  async function remove(ids: number[]) {
    await bookHighlightApi.remove(ids)
    list.value = list.value.filter((h) => !ids.includes(h.id))
    version.value++
  }

  const find = (id: number) => list.value.find((h) => h.id === id)

  return { list, version, load, marksFor, add, update, preview, remove, find }
}
