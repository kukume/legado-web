import { onBeforeUnmount, onMounted } from 'vue'
import { statsApi } from '@/api'
import { getToken } from '@/api/http'

/** 多久没有操作就不再计入阅读时间（朗读、自动阅读时除外） */
const IDLE_MS = 3 * 60 * 1000
/** 多久上报一次 */
const FLUSH_MS = 60 * 1000

/** 本地日期 yyyy-MM-dd（按用户所在时区统计“今天”） */
export function localDay(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/**
 * 阅读统计：页面在前台、最近有操作（或正在朗读 / 自动阅读）时每秒累计阅读时间；
 * 读完一章时由阅读页调用 addChars 累计字数。每分钟、页面切到后台、离开阅读页时上报
 */
export function useReadingStats(opts: {
  bookId: () => number
  /** 没有操作也算在读（朗读、自动阅读） */
  passiveReading: () => boolean
}) {
  let seconds = 0
  let chars = 0
  /** 正在累计的书：计时时记下来（离开阅读页时路由已经变了，不能在上报时再读） */
  let book = 0
  let lastInteraction = Date.now()
  let tickTimer: ReturnType<typeof setInterval> | null = null
  let flushTimer: ReturnType<typeof setInterval> | null = null

  function touch() {
    lastInteraction = Date.now()
  }

  /** 记下当前的书；换了书时先把上一本书的数据上报 */
  function syncBook(): boolean {
    const id = opts.bookId()
    if (!Number.isFinite(id) || id <= 0) return false
    if (book && id !== book) flush()
    book = id
    return true
  }

  function tick() {
    if (document.visibilityState !== 'visible' || !syncBook()) return
    if (Date.now() - lastInteraction < IDLE_MS || opts.passiveReading()) seconds++
  }

  function addChars(n: number) {
    if (n > 0 && syncBook()) chars += n
  }

  function take() {
    if ((!seconds && !chars) || !book) return null
    const r = { day: localDay(), bookId: book, seconds, chars }
    seconds = 0
    chars = 0
    return r
  }

  function flush() {
    const r = take()
    if (!r) return
    statsApi.report(r).catch(() => {
      // 上报失败（离线等）：放回去，下次一起上报
      seconds += r.seconds
      chars += r.chars
    })
  }

  /** 页面切到后台 / 关闭时：keepalive 请求在页面卸载后也能发出去 */
  function flushKeepalive() {
    const r = take()
    if (!r) return
    const token = getToken()
    fetch('/api/stats/reading', {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(r),
    }).catch(() => {})
  }

  function onVisibility() {
    if (document.visibilityState === 'hidden') flushKeepalive()
    else touch()
  }

  const events = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const
  onMounted(() => {
    tickTimer = setInterval(tick, 1000)
    flushTimer = setInterval(flush, FLUSH_MS)
    for (const e of events) window.addEventListener(e, touch, { passive: true, capture: true })
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', flushKeepalive)
  })
  onBeforeUnmount(() => {
    if (tickTimer) clearInterval(tickTimer)
    if (flushTimer) clearInterval(flushTimer)
    for (const e of events) window.removeEventListener(e, touch, { capture: true })
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('pagehide', flushKeepalive)
    flush()
  })

  return { addChars, touch, flush }
}
