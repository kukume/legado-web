/**
 * PDF 本地书：页面在浏览器里用 pdf.js 渲染（与 App 一样，正文是一页页的图）。
 *
 * - 上传前读出页数（服务器按页数生成目录：每 10 页一章），导入后把第一页渲染成封面上传
 * - 阅读时整本 PDF 只下载一次（已离线下载的直接用本地文件），按需渲染每一页
 *
 * pdf.js 较大，只在用到时加载。
 */
import type { PDFDocumentProxy } from 'pdfjs-dist'
import type { Book } from '@/api/types'
import { getToken, NetworkError } from '@/api/http'
import { getOfflineFile } from '@/offline/db'
import { t } from '@/i18n'

type Pdfjs = typeof import('pdfjs-dist')

let lib: Promise<Pdfjs> | null = null

function loadPdfjs(): Promise<Pdfjs> {
  if (!lib) {
    lib = Promise.all([import('pdfjs-dist'), import('pdfjs-dist/build/pdf.worker.min.mjs?url')]).then(([m, worker]) => {
      m.GlobalWorkerOptions.workerSrc = worker.default
      return m
    })
    lib.catch(() => (lib = null))
  }
  return lib
}

/** 字符映射表（中文 PDF 需要）、标准字体、图像解码 wasm：构建时复制到 /pdfjs/（见 vite.config.ts） */
const base = `${import.meta.env.BASE_URL}pdfjs/`
const assetOptions = {
  cMapUrl: `${base}cmaps/`,
  cMapPacked: true,
  standardFontDataUrl: `${base}standard_fonts/`,
  wasmUrl: `${base}wasm/`,
  iccUrl: `${base}iccs/`,
}

/** 打开的文档对应的加载任务（关闭文档要通过加载任务） */
const loadingTasks = new WeakMap<PDFDocumentProxy, { destroy(): Promise<void> }>()

async function openPdf(data: Uint8Array): Promise<PDFDocumentProxy> {
  const pdfjs = await loadPdfjs()
  const task = pdfjs.getDocument({ data, ...assetOptions })
  const doc = await task.promise
  loadingTasks.set(doc, task)
  return doc
}

function closePdf(doc: PDFDocumentProxy) {
  loadingTasks.get(doc)?.destroy().catch(() => {})
}

export function isPdfBook(book: Pick<Book, 'isLocal' | 'bookUrl'> | null | undefined): boolean {
  return !!book?.isLocal && /\.pdf$/i.test(book.bookUrl)
}

export function isPdfFile(file: File) {
  return /\.pdf$/i.test(file.name)
}

/** 页码（从 0 开始）；不是 PDF 页面时为 null */
export function pdfPageOf(src: string): number | null {
  const m = /^pdfpage:(\d+)/.exec(src)
  return m ? Number(m[1]) : null
}

/**
 * 上传前读取 PDF：页数，以及第一页渲染的封面（JPEG，失败时为 null）
 */
export async function inspectPdf(file: File): Promise<{ pages: number; cover: Blob | null }> {
  let doc: PDFDocumentProxy
  try {
    doc = await openPdf(new Uint8Array(await file.arrayBuffer()))
  } catch (e: any) {
    throw new Error(e?.name === 'PasswordException' ? t('不支持加密的 PDF') : t('无法读取 PDF：{0}', [e?.message ?? e]))
  }
  try {
    const cover = await renderCover(doc).catch(() => null)
    return { pages: doc.numPages, cover }
  } finally {
    closePdf(doc)
  }
}

async function renderCover(doc: PDFDocumentProxy): Promise<Blob | null> {
  const page = await doc.getPage(1)
  const scale = 600 / page.getViewport({ scale: 1 }).width
  const viewport = page.getViewport({ scale })
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(viewport.width)
  canvas.height = Math.min(Math.round(viewport.height), 1600)
  const ctx = canvas.getContext('2d')!
  // 透明背景的 PDF 转 JPEG 会变黑，先铺白底
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  // print 模式不用 requestAnimationFrame 分帧：上传时切到其他标签页（页面隐藏时 rAF 不触发）也能渲染完
  await page.render({ canvas, canvasContext: ctx, viewport, intent: 'print' }).promise
  page.cleanup()
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85))
}

// ---------------- 阅读 ----------------

/** 最近打开的几本书的 PDF（切换章节不用重新下载） */
const docs = new Map<number, Promise<PDFDocumentProxy>>()
const MAX_DOCS = 2

export function bookPdf(bookId: number): Promise<PDFDocumentProxy> {
  let p = docs.get(bookId)
  if (p) {
    // 最近使用的放到最后
    docs.delete(bookId)
    docs.set(bookId, p)
    return p
  }
  p = loadBookFile(bookId).then(openPdf)
  p.catch(() => docs.get(bookId) === p && docs.delete(bookId))
  docs.set(bookId, p)
  while (docs.size > MAX_DOCS) {
    const [oldId, old] = docs.entries().next().value!
    docs.delete(oldId)
    old.then(closePdf, () => {})
  }
  return p
}

/** 换了文件（重新上传、删除）时丢弃缓存 */
export function forgetBookPdf(bookId: number) {
  ratios.delete(bookId)
  const p = docs.get(bookId)
  if (!p) return
  docs.delete(bookId)
  p.then(closePdf, () => {})
}

async function loadBookFile(bookId: number): Promise<Uint8Array> {
  const local = await getOfflineFile(bookId).catch(() => undefined)
  if (local) return new Uint8Array(await local.arrayBuffer())
  return new Uint8Array(await fetchBookFile(bookId))
}

/** 从服务器下载 PDF 原文件（离线下载时也用） */
export async function fetchBookFile(bookId: number): Promise<ArrayBuffer> {
  let res: Response
  try {
    res = await fetch(`/api/shelf/${bookId}/local/file`, { headers: { Authorization: `Bearer ${getToken()}` } })
  } catch {
    throw new NetworkError()
  }
  if (!res.ok) {
    const data = await res.json().catch(() => null)
    throw new Error(data?.error || t('下载 PDF 失败（{0}）', [res.status]))
  }
  return res.arrayBuffer()
}

/** 页面的宽高比（高 / 宽），用于渲染前占位；按书缓存 */
const ratios = new Map<number, Map<number, number>>()

export function cachedRatio(bookId: number, index: number): number | undefined {
  return ratios.get(bookId)?.get(index)
}

export async function pageRatio(bookId: number, index: number): Promise<number> {
  const hit = cachedRatio(bookId, index)
  if (hit) return hit
  const page = await (await bookPdf(bookId)).getPage(index + 1)
  const v = page.getViewport({ scale: 1 })
  const r = v.height / v.width
  let m = ratios.get(bookId)
  if (!m) ratios.set(bookId, (m = new Map()))
  m.set(index, r)
  return r
}

/**
 * 显示一章之前先取出各页的比例（同时确认 PDF 能打开），页面一出现就是正确的高度，恢复阅读位置不会跳动
 */
export async function primePages(bookId: number, pages: number[]) {
  await Promise.all(pages.map((p) => pageRatio(bookId, p)))
}

/**
 * 把第 index 页（从 0 开始）渲染到 canvas，宽度为 cssWidth 像素（按设备像素比放大，保证清晰）
 */
export async function renderPage(doc: PDFDocumentProxy, index: number, canvas: HTMLCanvasElement, cssWidth: number, signal?: { cancelled: boolean }) {
  const page = await doc.getPage(index + 1)
  if (signal?.cancelled) return
  const dpr = Math.min(window.devicePixelRatio || 1, 3)
  const base = page.getViewport({ scale: 1 })
  // 画布太大时手机浏览器会渲染失败，限制在 4096 像素以内
  const scale = Math.min((cssWidth * dpr) / base.width, 4096 / Math.max(base.width, base.height))
  const viewport = page.getViewport({ scale })
  const off = document.createElement('canvas')
  off.width = Math.round(viewport.width)
  off.height = Math.round(viewport.height)
  const ctx = off.getContext('2d')!
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, off.width, off.height)
  const task = page.render({ canvas: off, canvasContext: ctx, viewport })
  try {
    await task.promise
  } finally {
    page.cleanup()
  }
  if (signal?.cancelled) return
  // 渲染完再一次性画上去，避免重新渲染时闪白
  canvas.width = off.width
  canvas.height = off.height
  canvas.getContext('2d')!.drawImage(off, 0, 0)
}
