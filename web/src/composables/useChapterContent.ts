import type { Ref } from 'vue'
import { shelfApi } from '@/api'
import { isNetworkError } from '@/api/http'
import type { Chapter, ChapterContent } from '@/api/types'
import { getOfflineContent } from '@/offline/db'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

/**
 * 阅读页的正文加载：内存缓存最近 30 章、合并同一章的并发请求、在线优先（离线或请求失败时用离线下载的正文）
 */
export function useChapterContent(bookId: Ref<number>, chapters: Ref<Chapter[]>) {
  const offline = useOfflineStore()
  const contentCache = new Map<number, ChapterContent>()
  const inflight = new Map<number, Promise<ChapterContent>>()

  /** 不完整正文（书源分页获取失败）的获取时间：只短暂缓存，之后再打开该章时重新获取 */
  const incompleteAt = new Map<number, number>()

  /** 换源等目录整体变化后加一：之前发出、之后才返回的请求是旧目录的正文，不能放进缓存 */
  let generation = 0

  function fetchContent(index: number, refresh = false): Promise<ChapterContent> {
    if (!refresh) {
      const c = contentCache.get(index)
      if (c && !(c.incomplete && Date.now() - (incompleteAt.get(index) ?? 0) > 30_000)) return Promise.resolve(c)
      const p = inflight.get(index)
      if (p) return p
    }
    const gen = generation
    const p = loadContent(index, refresh, gen)
      .then((c) => {
        if (gen !== generation) return c
        if (c.incomplete) incompleteAt.set(index, Date.now())
        else incompleteAt.delete(index)
        contentCache.set(index, c)
        if (contentCache.size > 30) contentCache.delete(contentCache.keys().next().value!)
        const ch = chapters.value[index]
        if (ch && !c.incomplete) ch.cached = true
        return c
      })
      .finally(() => {
        if (inflight.get(index) === p) inflight.delete(index)
      })
    inflight.set(index, p)
    return p
  }

  /** 清空缓存（换源后），丢弃正在进行的请求的结果 */
  function reset() {
    generation++
    contentCache.clear()
    inflight.clear()
    incompleteAt.clear()
  }

  /** 优先读取离线下载的正文，没有再请求服务器 */
  async function loadContent(index: number, refresh: boolean, gen: number): Promise<ChapterContent> {
    // 离线下载的正文是下载时应用了替换规则的结果，规则修改后会过时：在线时以服务器为准（服务器有缓存，很快），
    // 只有离线或请求失败时才用离线副本
    const offlineCopy = () =>
      getOfflineContent(bookId.value, index)
        .catch(() => undefined)
        .then((off) => (off ? { index, title: off.title, content: off.content, isVolume: false, cached: true } : null))
    if (!refresh && !offline.online) {
      const off = await offlineCopy()
      if (off) return off
    }
    try {
      const c = await shelfApi.content(bookId.value, index, refresh)
      if (gen === generation) offline.saveContent(bookId.value, index, c.title, c.content)
      return c
    } catch (e) {
      offline.markError(e)
      if (!refresh && isNetworkError(e)) {
        const off = await offlineCopy()
        if (off) return off
      }
      const ch = chapters.value[index]
      if (ch?.isVolume) return { index, title: ch.title, content: "", isVolume: true, cached: false }
      if (isNetworkError(e)) throw new Error(t('当前离线，这一章还没有下载'))
      throw e
    }
  }

  /** 预加载后面两章 */
  function prefetch(index: number) {
    for (const i of [index + 1, index + 2]) {
      if (i < chapters.value.length && !contentCache.has(i)) fetchContent(i).catch(() => {})
    }
  }

  return { contentCache, fetchContent, prefetch, reset }
}
