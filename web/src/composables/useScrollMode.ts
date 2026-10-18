import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { Chapter } from '@/api/types'
import type { Segment } from '@/components/reader/types'

/**
 * 阅读页的上下滚动模式：连续显示多章，滚到底部附近接上下一章、滚到顶部附近接上上一章，
 * 只保留最近的若干章，并根据滚动位置更新当前章节和进度
 */
export function useScrollMode(d: {
  chapters: Ref<Chapter[]>
  curIndex: Ref<number>
  curPos: Ref<number>
  /** 当前是否为滚动模式 */
  active: () => boolean
  menuVisible: Ref<boolean>
  loadSegment: (index: number) => Promise<Segment>
  /** 是否已缓存某章正文 / 预先加载 */
  isCached: (index: number) => boolean
  preload: (index: number) => void
  prefetch: (index: number) => void
  scheduleSave: () => void
  /** 每次滚动（更新选中文字工具栏的位置等） */
  onScrolled: () => void
}) {
  const scroller = ref<HTMLElement | null>(null)
  const segments = ref<Segment[]>([])
  const loadingMore = ref(false)
  const segRefs = new Map<number, HTMLElement>()

  function setSegRef(index: number, el: any) {
    if (el) segRefs.set(index, el as HTMLElement)
    else segRefs.delete(index)
  }

  async function openScroll(index: number, pos = 0) {
    loadFailStreak = 0
    prevFailed = -1
    segments.value = [{ index, title: d.chapters.value[index]?.title ?? '', paras: [], loading: true }]
    const seg = await d.loadSegment(index)
    segments.value = [seg]
    d.curIndex.value = index
    d.curPos.value = pos
    await nextTick()
    const el = segRefs.get(index)
    if (scroller.value && el) {
      scroller.value.scrollTop = el.offsetTop + (pos / 10000) * el.offsetHeight
    }
    d.prefetch(index)
    checkLoadMore()
    // 打开的位置靠近章节开头时，把上一章也接上，可以继续往上滑
    schedulePrepend()
  }

  // ---- 往上滑到顶部附近时接上上一章 ----
  // 插入到前面会让内容整体下移，需要同时校正滚动位置；手机上惯性滚动期间设置 scrollTop 会被浏览器覆盖（iOS 尤其明显），
  // 所以等手指离开、滚动停下后再插入
  const loadingPrev = ref(false)
  /** 上一章加载失败的章节序号（不再自动重试，避免一直请求） */
  let prevFailed = -1
  let prependTimer: ReturnType<typeof setTimeout> | null = null
  /** 最近一次滚动的时间 */
  let lastScrollAt = 0

  function schedulePrepend() {
    if (prependTimer) clearTimeout(prependTimer)
    prependTimer = setTimeout(checkPrepend, 200)
  }

  function checkPrepend() {
    prependTimer = null
    const s = scroller.value
    if (!s || !s.clientHeight || !d.active()) return
    if (s.scrollTop < s.clientHeight) prependPrev()
  }

  /** 等手指离开、滚动停下（最多等 4 秒） */
  async function waitScrollIdle() {
    const start = Date.now()
    while ((touching || Date.now() - lastScrollAt < 150) && Date.now() - start < 4000) {
      await new Promise((r) => setTimeout(r, 50))
    }
  }

  async function prependPrev() {
    const first = segments.value[0]
    if (!first || first.loading || loadingPrev.value) return
    const prev = first.index - 1
    if (prev < 0 || prev === prevFailed) return
    loadingPrev.value = true
    try {
      const seg = await d.loadSegment(prev)
      if (seg.error) {
        prevFailed = prev
        return
      }
      await waitScrollIdle()
      const s = scroller.value
      const anchor = segRefs.get(first.index)
      // 等待期间切换了章节或模式：放弃这次插入
      if (!s || !anchor || segments.value[0]?.index !== first.index || !d.active()) return
      // 以原来的第一章为参照：记下它相对视口的位置，插入后恢复（浏览器的滚动锚定可能已经调整过，直接按参照计算）
      const offset = anchor.offsetTop - s.scrollTop
      segments.value.unshift(seg)
      await nextTick()
      s.scrollTop = anchor.offsetTop - offset
      scheduleTrim()
      if (prev > 0 && !d.isCached(prev - 1)) d.preload(prev - 1)
    } finally {
      loadingPrev.value = false
    }
  }

  async function appendNext() {
    const last = segments.value[segments.value.length - 1]
    if (!last || loadingMore.value) return
    const next = last.index + 1
    if (next >= d.chapters.value.length) return
    loadingMore.value = true
    let appended: Segment | null = null
    try {
      const seg = await d.loadSegment(next)
      if (segments.value[segments.value.length - 1]?.index === last.index) {
        segments.value.push(seg)
        appended = seg
        scheduleTrim()
        d.prefetch(next)
      }
    } finally {
      loadingMore.value = false
      if (appended) loadFailStreak = appended.error ? loadFailStreak + 1 : 0
      // 加载完再检查一次：内容仍不满一屏（章节很短、加载失败，或加载期间切换了章节、这次的结果被丢弃）时继续加载。
      // 否则页面滚不动，也就不会再触发滚动加载，表现为滚动条占满、滑不动。连续失败 3 次后不再自动加载
      if (loadFailStreak < 3) nextTick(checkLoadMore)
    }
  }

  /** 连续加载失败的章节数（书源不可用时避免一直自动请求） */
  let loadFailStreak = 0

  // 滚动区域尺寸变化（刚显示出来、窗口大小 / 字号变化、转屏）时检查是否需要加载下一章：
  // 打开章节时如果滚动区域还没有高度，当时的检查会以为不需要加载
  let scrollerCheckTimer: ReturnType<typeof setTimeout> | null = null
  const scrollerObserver = typeof ResizeObserver !== 'undefined'
    ? new ResizeObserver(() => {
        if (scrollerCheckTimer) clearTimeout(scrollerCheckTimer)
        scrollerCheckTimer = setTimeout(checkLoadMore, 100)
      })
    : null
  watch(scroller, (el, old) => {
    if (old) scrollerObserver?.unobserve(old)
    if (el) scrollerObserver?.observe(el)
  })
  // 从后台切回页面时也检查一次
  function onVisible() {
    if (document.visibilityState === 'visible') checkLoadMore()
  }
  document.addEventListener('visibilitychange', onVisible)

  // ---- 只保留最近的若干章，避免 DOM 过大 ----
  // 移除最前面的章节会让内容整体上移，需要同时校正滚动位置。手机上惯性滚动期间设置 scrollTop 会被浏览器覆盖
  // （iOS 尤其明显），表现为突然跳到后面一章，所以等手指离开、滚动停下后再移除。
  const KEEP_SEGMENTS = 6
  let trimTimer: ReturnType<typeof setTimeout> | null = null
  let touching = false

  function scheduleTrim() {
    if (trimTimer) clearTimeout(trimTimer)
    trimTimer = null
    if (segments.value.length <= KEEP_SEGMENTS) return
    trimTimer = setTimeout(trimSegments, 800)
  }

  async function trimSegments() {
    trimTimer = null
    const s = scroller.value
    if (!s || touching || segments.value.length <= KEEP_SEGMENTS) return
    // 正在读的章节在前半部分（往回读，前面接上了上一章）：移除后面离得远的章节。
    // 它们在视口下方，移除不影响滚动位置
    const at = segments.value.findIndex((seg) => seg.index === d.curIndex.value)
    if (at >= 0 && at < segments.value.length / 2) {
      segments.value.splice(Math.max(KEEP_SEGMENTS, at + 2))
      return
    }
    // 以第一个保留的章节为参照：记下它相对视口的位置，移除后恢复。
    // 不能直接减去被移除章节的高度：Chrome 等浏览器的滚动锚定会自动调整一次，再减就会跳回前一章
    const removeCount = segments.value.length - KEEP_SEGMENTS
    const anchor = segRefs.get(segments.value[removeCount].index)
    if (!anchor) return
    // 正在读的位置还在要移除的章节里（例如往回翻了），这次不移除
    if (anchor.offsetTop > s.scrollTop) return
    const offset = anchor.offsetTop - s.scrollTop
    segments.value.splice(0, removeCount)
    await nextTick()
    s.scrollTop = anchor.offsetTop - offset
  }

  function onTouchStart() {
    touching = true
    if (trimTimer) clearTimeout(trimTimer)
    trimTimer = null
  }

  function onTouchEnd() {
    touching = false
    scheduleTrim()
  }

  function checkLoadMore() {
    const s = scroller.value
    // 滚动区域还没有高度（尚未显示）时无法判断，等尺寸变化后再检查
    if (!s || !s.clientHeight || !d.active()) return
    if (s.scrollHeight - s.scrollTop - s.clientHeight < s.clientHeight * 1.5) appendNext()
  }

  let lastScrollTop = 0
  function onScroll() {
    const s = scroller.value
    if (!s) return
    d.onScrolled()
    const top = s.scrollTop + 60
    for (const seg of segments.value) {
      const el = segRefs.get(seg.index)
      if (!el) continue
      if (top >= el.offsetTop && top < el.offsetTop + el.offsetHeight) {
        if (d.curIndex.value !== seg.index) d.curIndex.value = seg.index
        d.curPos.value = Math.max(0, Math.min(10000, ((s.scrollTop - el.offsetTop) / Math.max(1, el.offsetHeight)) * 10000))
        break
      }
    }
    // 只有明显滚动才收起菜单（点击时可能产生 1px 左右的滚动）
    if (Math.abs(s.scrollTop - lastScrollTop) > 30) {
      lastScrollTop = s.scrollTop
      if (d.menuVisible.value) d.menuVisible.value = false
    }
    d.scheduleSave()
    checkLoadMore()
    lastScrollAt = Date.now()
    if (s.scrollTop < s.clientHeight) schedulePrepend()
    // 滚动（包括惯性滚动）还在继续时推迟移除旧章节
    if (trimTimer) scheduleTrim()
  }

  /** 呼出菜单时记下滚动位置：之后滚动超过 30px 才收起菜单 */
  function rememberScrollTop() {
    lastScrollTop = scroller.value?.scrollTop ?? 0
  }

  /** 翻一屏 */
  function scrollBy(dir: 1 | -1) {
    const s = scroller.value
    if (!s) return
    s.scrollBy({ top: dir * (s.clientHeight - 60), behavior: 'smooth' })
  }

  onBeforeUnmount(() => {
    if (prependTimer) clearTimeout(prependTimer)
    if (trimTimer) clearTimeout(trimTimer)
    if (scrollerCheckTimer) clearTimeout(scrollerCheckTimer)
    scrollerObserver?.disconnect()
    document.removeEventListener('visibilitychange', onVisible)
  })

  return {
    scroller, segments, segRefs, setSegRef, loadingMore,
    openScroll, appendNext, onScroll, onTouchStart, onTouchEnd, scrollBy, rememberScrollTop,
  }
}
