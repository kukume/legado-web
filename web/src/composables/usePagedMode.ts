import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import type { Chapter } from '@/api/types'
import type { PageAnim } from '@/stores/reader'
import type { Segment } from '@/components/reader/types'
import { usePageAnimation } from '@/composables/usePageAnimation'
import { t } from '@/i18n'

/**
 * 阅读页的分页模式：左右翻页（CSS 多栏，按栏宽平移）、上下翻页（单栏，按完整的文字行切页），
 * 章内翻页、跨章翻页和翻页动画
 */
export function usePagedMode(d: {
  chapters: Ref<Chapter[]>
  curIndex: Ref<number>
  curPos: Ref<number>
  /** 当前是否为分页模式 / 上下翻页 */
  active: () => boolean
  isVertical: ComputedRef<boolean>
  /** 左右翻页时一屏显示两页 */
  isDouble: ComputedRef<boolean>
  pageAnim: ComputedRef<PageAnim>
  loadSegment: (index: number) => Promise<Segment>
  prefetch: (index: number) => void
  scheduleSave: () => void
}) {
  const message = useMessage()
  const pager = ref<HTMLElement | null>(null)
  const columns = ref<HTMLElement | null>(null)
  const pageClip = ref<HTMLElement | null>(null)
  const pageSeg = ref<Segment | null>(null)
  const page = ref(0)
  const pageCount = ref(1)
  const pageLoading = ref(false)
  /** width 为一屏的正文宽度（双页时包含两页和中间的间距），colWidth 为每页（每栏）的宽度 */
  const pageMetrics = reactive({ width: 0, gap: 48, height: 0, colWidth: 0 })
  /** 上下翻页：每页顶部在正文中的偏移 */
  const pageStarts = ref<number[]>([0])
  /** 换章时暂时关闭翻页过渡，避免跳到最后一页时连续滑过所有页 */
  const noTransition = ref(false)

  const { snapshotPage, snapshotFull, animatePages, finishPageAnims } = usePageAnimation({
    pager, pageClip, isVertical: d.isVertical, pageMetrics, pageLoading, pageAnim: d.pageAnim,
  })

  /**
   * 打开章节（分页模式）
   * anim：翻页跨章时的滑动方向，旧页面滑出、新页面滑入，和章内翻页一致
   */
  async function openPage(index: number, pos = 0, toLast = false, anim: "next" | "prev" | null = null) {
    finishPageAnims()
    const kind = d.pageAnim.value
    const ghost = !anim || kind === "none" ? null : kind === "slide" ? snapshotPage() : snapshotFull()
    let slid = false
    // 已缓存的章节几乎瞬间加载完，只有加载较慢时才显示加载中
    const loadingTimer = setTimeout(() => (pageLoading.value = true), 200)
    try {
      await nextTick()
      await measurePages()
      const seg = await d.loadSegment(index)
      noTransition.value = true
      pageSeg.value = seg
      d.curIndex.value = index
      clearTimeout(loadingTimer)
      pageLoading.value = false
      await nextTick()
      await measurePages()
      if (toLast) page.value = pageCount.value - 1
      else page.value = Math.min(pageCount.value - 1, Math.floor((pos / 10000) * pageCount.value))
      await nextTick()
      void columns.value?.offsetWidth // 让无过渡的位置先生效
      noTransition.value = false
      if (ghost && anim) {
        animatePages(ghost, anim)
        slid = true
      }
      updatePagePos()
      d.prefetch(index)
    } finally {
      clearTimeout(loadingTimer)
      pageLoading.value = false
      noTransition.value = false
      if (ghost && !slid) ghost.remove()
    }
  }

  /**
   * 章内翻页：平移用正文的 CSS 过渡；其他动画先拍下当前页的快照，无过渡地切到新页，再在快照和新页之间做动画
   */
  async function turnPage(dir: "next" | "prev") {
    finishPageAnims()
    const kind = d.pageAnim.value
    const old = kind === "slide" || kind === "none" ? null : snapshotFull()
    if (kind !== "slide") noTransition.value = true
    page.value += dir === "next" ? 1 : -1
    updatePagePos()
    if (kind === "slide") return
    await nextTick()
    void columns.value?.offsetWidth // 让无过渡的位置先生效
    noTransition.value = false
    if (old) animatePages(old, dir)
  }

  async function measurePages() {
    const c = columns.value
    const p = pager.value
    if (!c || !p) return
    const style = getComputedStyle(p)
    if (d.isVertical.value) return measureVertical(c, p, style)
    const padL = parseFloat(style.paddingLeft)
    const padR = parseFloat(style.paddingRight)
    const w = Math.floor(p.clientWidth - padL - padR)
    if (w <= 0) return // 页面不可见时无法测量，等 ResizeObserver 触发
    // 列间距不小于左右内边距之和，否则相邻页的文字会露在内边距里
    const gap = Math.max(48, Math.ceil(padL + padR))
    // 双页：一屏两栏，中间的间距与屏与屏之间的间距相同，每屏平移的距离不变
    const colWidth = d.isDouble.value ? (w - gap) / 2 : w
    if (w !== pageMetrics.width || gap !== pageMetrics.gap || colWidth !== pageMetrics.colWidth) {
      pageMetrics.width = w
      pageMetrics.gap = gap
      pageMetrics.colWidth = colWidth
      // 等待新的列宽生效后再计算页数
      await nextTick()
    }
    const total = c.scrollWidth
    const cols = Math.max(1, Math.round((total + gap) / (colWidth + gap)))
    pageCount.value = d.isDouble.value ? Math.ceil(cols / 2) : cols
    if (page.value >= pageCount.value) page.value = pageCount.value - 1
  }

  /**
   * 上下翻页分页：按文字行的位置切分，每页只放完整的行，不会把一行切成两半。
   * 逐个检查正文容器的直接子元素（章节名、段落、图片），所以正文组件不能再包一层元素
   */
  async function measureVertical(c: HTMLElement, p: HTMLElement, style: CSSStyleDeclaration) {
    const h = Math.floor(p.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom))
    if (h <= 0) return
    pageMetrics.height = h
    await nextTick()
    const base = c.getBoundingClientRect().top
    const lines: { top: number; bottom: number }[] = []
    const range = document.createRange()
    for (const el of Array.from(c.children) as HTMLElement[]) {
      if (el.tagName === "IMG" || !el.textContent?.trim()) {
        const r = el.getBoundingClientRect()
        if (r.height) lines.push({ top: r.top - base, bottom: r.bottom - base })
        continue
      }
      range.selectNodeContents(el)
      for (const r of Array.from(range.getClientRects())) {
        if (r.height) lines.push({ top: r.top - base, bottom: r.bottom - base })
      }
    }
    lines.sort((a, b) => a.top - b.top)
    const starts = [0]
    let start = 0
    for (const l of lines) {
      if (l.bottom - start > h + 0.5 && l.top > start) {
        start = l.top
        starts.push(start)
      }
    }
    pageStarts.value = starts
    pageCount.value = starts.length
    if (page.value >= pageCount.value) page.value = pageCount.value - 1
  }

  /** 上下翻页：当前页可见高度（到下一页开头为止，避免露出半行） */
  const clipStyle = computed(() => {
    if (!d.isVertical.value) return {}
    const starts = pageStarts.value
    const next = starts[page.value + 1]
    const h = pageMetrics.height
    return { height: `${next !== undefined ? Math.min(h, next - starts[page.value]) : h}px` }
  })

  function updatePagePos() {
    d.curPos.value = pageCount.value > 1 ? (page.value / pageCount.value) * 10000 : 0
    d.scheduleSave()
  }

  const baseColumnStyle = computed(() =>
    d.isVertical.value
      ? { transform: `translateY(${-(pageStarts.value[page.value] ?? 0)}px)`, "--page-h": `${pageMetrics.height}px` }
      : {
          columnWidth: `${pageMetrics.colWidth || pageMetrics.width}px`,
          columnGap: `${pageMetrics.gap}px`,
          transform: `translateX(${-page.value * (pageMetrics.width + pageMetrics.gap)}px)`,
        },
  )
  const columnStyle = computed(() => ({
    ...(noTransition.value ? { transition: "none" } : {}),
    ...baseColumnStyle.value,
  }))

  async function nextPage() {
    if (page.value < pageCount.value - 1) {
      await turnPage("next")
    } else if (d.curIndex.value < d.chapters.value.length - 1) {
      await openPage(d.curIndex.value + 1, 0, false, "next")
    } else {
      message.info(t('已经是最后一章了'))
    }
  }

  async function prevPage() {
    if (page.value > 0) {
      await turnPage("prev")
    } else if (d.curIndex.value > 0) {
      await openPage(d.curIndex.value - 1, 0, true, "prev")
    } else {
      message.info(t('已经是第一章了'))
    }
  }

  /** 排版或尺寸变化后重新分页，保持在同样的阅读进度 */
  async function remeasure() {
    const ratio = d.curPos.value
    await measurePages()
    page.value = Math.min(pageCount.value - 1, Math.floor((ratio / 10000) * pageCount.value))
  }

  // 切换单页 / 双页后重新分页
  watch(d.isDouble, () => {
    if (d.active()) nextTick(remeasure)
  })

  let resizeTimer: ReturnType<typeof setTimeout> | null = null
  function onResize() {
    if (!d.active()) return
    if (resizeTimer) clearTimeout(resizeTimer)
    resizeTimer = setTimeout(remeasure, 150)
  }
  const resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => onResize()) : null
  watch(pager, (el, old) => {
    if (old) resizeObserver?.unobserve(old)
    if (el) resizeObserver?.observe(el)
  })
  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    if (resizeTimer) clearTimeout(resizeTimer)
  })

  return {
    pager, columns, pageClip, pageSeg, page, pageCount, pageLoading, pageMetrics, pageStarts,
    clipStyle, columnStyle, openPage, measurePages, remeasure, onResize, updatePagePos, nextPage, prevPage,
  }
}
