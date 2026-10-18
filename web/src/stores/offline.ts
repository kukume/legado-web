import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { shelfApi } from '@/api'
import { isNetworkError } from '@/api/http'
import type { Book, Chapter } from '@/api/types'
import {
  deleteOfflineBook, getLocalProgress, getOfflineBook, getOfflineFile, listDirtyProgress, listOfflineBooks, markProgressSynced,
  offlineContentIndexes, putLocalProgress, putOfflineBook, putOfflineContent, putOfflineFile, setOfflineUser,
} from '@/offline/db'
import { fetchBookFile, isPdfBook } from '@/utils/pdf'
import { t } from '@/i18n'

export interface DownloadTask {
  bookId: number
  bookName: string
  total: number
  done: number
  failed: number
  running: boolean
  error?: string
  cancelled?: boolean
}

/** 同时下载的章节数 */
const CONCURRENCY = 3

export const useOfflineStore = defineStore('offline', () => {
  const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
  /** 已离线下载的书 */
  const bookIds = ref(new Set<number>())
  const tasks = reactive(new Map<number, DownloadTask>())
  let userId: number | null = null

  /** 切换到某个用户的离线数据 */
  async function init(uid: number | null | undefined) {
    if ((uid ?? 0) === userId) return
    userId = uid ?? 0
    setOfflineUser(userId)
    await reloadBookIds()
  }

  async function reloadBookIds() {
    try {
      bookIds.value = new Set((await listOfflineBooks()).map((b) => b.bookId))
    } catch {
      bookIds.value = new Set()
    }
  }

  function setOnline(v: boolean) {
    online.value = v
  }

  /** 服务器请求失败时标记离线（navigator.onLine 在连着 WiFi 但服务器不可达时仍是 true） */
  function markError(e: unknown) {
    if (isNetworkError(e)) online.value = false
  }

  /**
   * 离线下载章节：indexes 为空时下载全部。已下载过的章节会跳过。
   */
  async function download(bookId: number, range?: { start: number; count: number }) {
    const existing = tasks.get(bookId)
    if (existing?.running) return existing
    const [book, chapters] = await Promise.all([shelfApi.get(bookId), shelfApi.chapters(bookId)])
    // PDF 的正文是在浏览器里渲染的页面：离线阅读需要原文件
    if (isPdfBook(book) && !(await getOfflineFile(bookId))) {
      await putOfflineFile(bookId, new Blob([await fetchBookFile(bookId)], { type: 'application/pdf' }))
    }
    await putOfflineBook(book, chapters)
    bookIds.value = new Set(bookIds.value).add(bookId)

    const have = await offlineContentIndexes(bookId)
    const start = range ? Math.max(0, range.start) : 0
    const end = range ? Math.min(chapters.length, start + range.count) : chapters.length
    const todo = chapters.slice(start, end).filter((c) => !c.isVolume && !have.has(c.index)).map((c) => c.index)

    tasks.set(bookId, { bookId, bookName: book.name, total: todo.length, done: 0, failed: 0, running: true })
    // 必须从 reactive Map 中取出代理对象再修改，界面才会更新
    const task = tasks.get(bookId)!
    let next = 0
    const worker = async () => {
      while (next < todo.length && !task.cancelled) {
        const index = todo[next++]
        try {
          const c = await shelfApi.content(bookId, index)
          // 不完整的章节（书源分页获取失败）不保存，算作失败，之后重新下载时会再获取
          if (c.incomplete) {
            task.failed++
            continue
          }
          await putOfflineContent(bookId, index, c.title, c.content)
          task.done++
        } catch (e: any) {
          if (isNetworkError(e)) {
            task.cancelled = true
            task.error = t('网络中断，下载已暂停')
            markError(e)
          } else if (e?.name === 'QuotaExceededError' || /quota/i.test(String(e?.message))) {
            task.cancelled = true
            task.error = t('存储空间不足')
          } else {
            task.failed++
          }
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, todo.length) }, worker))
    task.running = false
    return task
  }

  function cancel(bookId: number) {
    const task = tasks.get(bookId)
    if (task?.running) {
      task.cancelled = true
      task.error = t('已取消')
    }
  }

  async function remove(bookId: number) {
    cancel(bookId)
    await deleteOfflineBook(bookId)
    tasks.delete(bookId)
    const s = new Set(bookIds.value)
    s.delete(bookId)
    bookIds.value = s
  }

  /**
   * 在线阅读已下载的书时，顺手更新本地的书籍信息和目录。
   * 换源后章节序号对不上，旧的正文全部删除（书仍保持离线状态，之后读到的章节会重新保存）。
   */
  async function refreshBook(book: Book, chapters: Chapter[]) {
    if (!bookIds.value.has(book.id)) return
    try {
      const old = await getOfflineBook(book.id)
      if (old && old.book.bookUrl !== book.bookUrl) await deleteOfflineBook(book.id)
      await putOfflineBook(book, chapters)
    } catch {
      /* ignore */
    }
  }

  /** 在线读到的章节：已下载的书顺手存下来 */
  async function saveContent(bookId: number, index: number, title: string, content: string) {
    if (bookIds.value.has(bookId)) await putOfflineContent(bookId, index, title, content).catch(() => {})
  }

  // ---------------- 阅读进度 ----------------

  /**
   * 记录进度：先写本地，再尝试同步到服务器。
   * 服务器按时间比较，只接受更新的进度（多设备以最后阅读的为准）。
   */
  async function saveProgress(bookId: number, index: number, pos: number, title: string) {
    const time = Date.now()
    await putLocalProgress({ bookId, index, pos, title, time, dirty: true }).catch(() => {})
    try {
      await shelfApi.progress(bookId, index, pos, title, time)
      await markProgressSynced(bookId, time).catch(() => {})
      online.value = true
    } catch (e) {
      markError(e)
    }
  }

  /** 把离线期间的进度同步到服务器 */
  let syncing = false
  async function syncProgress() {
    if (syncing) return 0
    syncing = true
    let n = 0
    try {
      for (const p of await listDirtyProgress()) {
        try {
          await shelfApi.progress(p.bookId, p.index, p.pos, p.title, p.time)
          await markProgressSynced(p.bookId, p.time)
          n++
        } catch (e) {
          if (isNetworkError(e)) {
            markError(e)
            break
          }
          // 书已被删除等：不再重试
          await markProgressSynced(p.bookId, p.time).catch(() => {})
        }
      }
      if (n) online.value = true
    } catch {
      /* 本地存储不可用 */
    } finally {
      syncing = false
    }
    return n
  }

  /** 服务器进度与本地未同步进度中较新的一个（已同步的本地记录以服务器为准，避免手机时间不准） */
  async function resolveProgress(book: Book): Promise<{ index: number; pos: number; local: boolean }> {
    const local = await getLocalProgress(book.id).catch(() => undefined)
    if (local?.dirty && local.time > (book.durChapterTime || 0)) return { index: local.index, pos: local.pos, local: true }
    return { index: book.durChapterIndex, pos: book.durChapterPos, local: false }
  }

  /** 离线时读取书籍（书籍信息使用本地进度） */
  async function loadOfflineBook(bookId: number) {
    const rec = await getOfflineBook(bookId)
    if (!rec) return null
    const p = await getLocalProgress(bookId).catch(() => undefined)
    const book = { ...rec.book }
    if (p && p.time > (book.durChapterTime || 0)) {
      Object.assign(book, { durChapterIndex: p.index, durChapterPos: p.pos, durChapterTitle: p.title, durChapterTime: p.time })
    }
    return { book, chapters: rec.chapters }
  }

  /** 离线书架 */
  async function loadOfflineShelf(): Promise<Book[]> {
    const list = await listOfflineBooks()
    const books = await Promise.all(list.map((r) => loadOfflineBook(r.bookId)))
    return books.filter((b): b is { book: Book; chapters: Chapter[] } => !!b).map((b) => b.book)
  }

  return {
    online, bookIds, tasks, init, reloadBookIds, setOnline, markError, download, cancel, remove, refreshBook,
    saveContent, saveProgress, syncProgress, resolveProgress, loadOfflineBook, loadOfflineShelf,
  }
})
