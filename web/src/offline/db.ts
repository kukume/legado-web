/**
 * 离线阅读的本地存储（IndexedDB）
 *
 * - books：书籍信息 + 目录
 * - contents：章节正文
 * - progress：本地阅读进度（dirty 表示还没同步到服务器）
 * - files：本地书的原文件（PDF 的正文是在浏览器里渲染的页面，离线时需要原文件）
 *
 * 所有记录都带 userId，同一浏览器切换账号时数据互不影响。
 */
import type { Book, Chapter } from '@/api/types'
import { t } from '@/i18n'

const DB_NAME = 'legado-offline'
const DB_VERSION = 2

export interface OfflineBook {
  userId: number
  bookId: number
  book: Book
  chapters: Chapter[]
  savedAt: number
}

export interface OfflineContent {
  userId: number
  bookId: number
  index: number
  title: string
  content: string
  size: number
}

export interface LocalProgress {
  userId: number
  bookId: number
  index: number
  pos: number
  title: string
  time: number
  dirty: boolean
}

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') return reject(new Error(t('当前浏览器不支持离线存储')))
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains('books')) {
        db.createObjectStore('books', { keyPath: ['userId', 'bookId'] }).createIndex('byUser', 'userId')
      }
      if (!db.objectStoreNames.contains('contents')) {
        db.createObjectStore('contents', { keyPath: ['userId', 'bookId', 'index'] })
          .createIndex('byBook', ['userId', 'bookId'])
      }
      if (!db.objectStoreNames.contains('progress')) {
        db.createObjectStore('progress', { keyPath: ['userId', 'bookId'] }).createIndex('byUser', 'userId')
      }
      if (!db.objectStoreNames.contains('files')) {
        db.createObjectStore('files', { keyPath: ['userId', 'bookId'] })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
    req.onblocked = () => reject(new Error(t('离线存储被其他页面占用，请关闭其他标签页后重试')))
  })
  dbPromise.catch(() => (dbPromise = null))
  return dbPromise
}

function promisify<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function store(name: string, mode: IDBTransactionMode = 'readonly') {
  const db = await openDb()
  return db.transaction(name, mode).objectStore(name)
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error ?? new Error(t('写入被中止（可能是存储空间不足）')))
  })
}

// 不同用户的数据分开保存
let currentUserId = 0
export function setOfflineUser(id: number | null | undefined) {
  currentUserId = id ?? 0
}
const uid = () => currentUserId

// ---------------- 书籍 ----------------

export async function getOfflineBook(bookId: number): Promise<OfflineBook | undefined> {
  return promisify((await store('books')).get([uid(), bookId]))
}

export async function listOfflineBooks(): Promise<OfflineBook[]> {
  return promisify((await store('books')).index('byUser').getAll(uid()))
}

export async function putOfflineBook(book: Book, chapters: Chapter[]) {
  const rec: OfflineBook = { userId: uid(), bookId: book.id, book, chapters, savedAt: Date.now() }
  await promisify((await store('books', 'readwrite')).put(rec))
}

/** 删除一本书的全部离线数据（书籍信息、正文、本地进度） */
export async function deleteOfflineBook(bookId: number) {
  const db = await openDb()
  const tx = db.transaction(['books', 'contents', 'progress', 'files'], 'readwrite')
  tx.objectStore('books').delete([uid(), bookId])
  tx.objectStore('progress').delete([uid(), bookId])
  tx.objectStore('files').delete([uid(), bookId])
  const range = IDBKeyRange.bound([uid(), bookId, -Infinity], [uid(), bookId, Infinity])
  tx.objectStore('contents').delete(range)
  await txDone(tx)
}

// ---------------- 原文件 ----------------

export interface OfflineFile {
  userId: number
  bookId: number
  blob: Blob
}

export async function getOfflineFile(bookId: number): Promise<Blob | undefined> {
  const rec: OfflineFile | undefined = await promisify((await store('files')).get([uid(), bookId]))
  return rec?.blob
}

export async function putOfflineFile(bookId: number, blob: Blob) {
  const rec: OfflineFile = { userId: uid(), bookId, blob }
  await promisify((await store('files', 'readwrite')).put(rec))
}

// ---------------- 正文 ----------------

export async function getOfflineContent(bookId: number, index: number): Promise<OfflineContent | undefined> {
  return promisify((await store('contents')).get([uid(), bookId, index]))
}

export async function putOfflineContent(bookId: number, index: number, title: string, content: string) {
  const rec: OfflineContent = { userId: uid(), bookId, index, title, content, size: content.length * 2 }
  await promisify((await store('contents', 'readwrite')).put(rec))
}

/** 已下载的章节序号 */
export async function offlineContentIndexes(bookId: number): Promise<Set<number>> {
  const keys = await promisify((await store('contents')).index('byBook').getAllKeys([uid(), bookId]))
  return new Set(keys.map((k) => (k as [number, number, number])[2]))
}

/** 每本书已下载的章节数和大概占用（字节） */
export async function offlineStats(): Promise<Map<number, { count: number; size: number }>> {
  const result = new Map<number, { count: number; size: number }>()
  // PDF 等本地书的原文件也算在占用里
  const files: OfflineFile[] = await promisify((await store('files')).getAll(IDBKeyRange.bound([uid(), -Infinity], [uid(), Infinity])))
  for (const f of files) result.set(f.bookId, { count: 0, size: f.blob.size })
  const s = await store('contents')
  return new Promise((resolve, reject) => {
    const req = s.openCursor(IDBKeyRange.bound([uid(), -Infinity, -Infinity], [uid(), Infinity, Infinity]))
    req.onsuccess = () => {
      const cur = req.result
      if (!cur) return resolve(result)
      const v = cur.value as OfflineContent
      const r = result.get(v.bookId) ?? { count: 0, size: 0 }
      r.count++
      r.size += v.size
      result.set(v.bookId, r)
      cur.continue()
    }
    req.onerror = () => reject(req.error)
  })
}

// ---------------- 阅读进度 ----------------

export async function getLocalProgress(bookId: number): Promise<LocalProgress | undefined> {
  return promisify((await store('progress')).get([uid(), bookId]))
}

export async function putLocalProgress(p: Omit<LocalProgress, 'userId'>) {
  await promisify((await store('progress', 'readwrite')).put({ ...p, userId: uid() }))
}

export async function listDirtyProgress(): Promise<LocalProgress[]> {
  const all: LocalProgress[] = await promisify((await store('progress')).index('byUser').getAll(uid()))
  return all.filter((p) => p.dirty)
}

/** 同步成功后清除 dirty（期间又有新进度时保留） */
export async function markProgressSynced(bookId: number, time: number) {
  const s = await store('progress', 'readwrite')
  const cur: LocalProgress | undefined = await promisify(s.get([uid(), bookId]))
  if (cur && cur.time === time && cur.dirty) await promisify(s.put({ ...cur, dirty: false }))
}
