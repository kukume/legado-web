import { onBeforeUnmount, onMounted, reactive } from 'vue'

/** iPhone / iPad（iPadOS 的 Safari 默认伪装成 Mac，用触摸点数区分） */
const isIOS =
  typeof navigator !== 'undefined' &&
  (/iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1))

/**
 * 选区在屏幕上可见的部分：各行矩形中落在 bounds（正文区域，已限制在视口内）里的那些的外框；完全不可见时返回 null。
 * 翻页模式下后面几页的栏虽然被页面裁掉，位置可能仍在视口内，所以要按正文区域而不是视口判断
 */
function visibleRect(range: Range, bounds: DOMRect): { left: number; right: number; top: number; bottom: number; width: number } | null {
  const bl = Math.max(0, bounds.left)
  const br = Math.min(window.innerWidth, bounds.right)
  const bt = Math.max(0, bounds.top)
  const bb = Math.min(window.innerHeight, bounds.bottom)
  let left = Infinity
  let right = -Infinity
  let top = Infinity
  let bottom = -Infinity
  for (const q of Array.from(range.getClientRects())) {
    if (!q.width || q.right <= bl || q.left >= br || q.bottom <= bt || q.top >= bb) continue
    left = Math.min(left, q.left)
    right = Math.max(right, q.right)
    top = Math.min(top, q.top)
    bottom = Math.max(bottom, q.bottom)
  }
  return left === Infinity ? null : { left, right, top, bottom, width: right - left }
}

/**
 * 阅读页选中文字：选区变化时计算工具栏位置、选中文字所在的章节和章内位置
 */
export function useTextSelection(opts: {
  /** 选区必须在这个元素内（滚动区域或翻页容器） */
  root: () => HTMLElement | null
  /** 滚动模式下各章节的元素（用于计算选中文字在本章中的位置），翻页模式返回 null */
  chapterEl: (index: number) => HTMLElement | null | undefined
  /** 找不到段落时使用的章节序号 */
  fallbackChapter: () => number
}) {
  /** prefix：所在段落开头到选区起点的文字（从选中处开始朗读时用来定位） */
  const selection = reactive({ show: false, text: '', x: 0, y: 0, chapter: -1, para: -1, prefix: '', paraTop: 0, segHeight: 0 })
  let timer: ReturnType<typeof setTimeout> | null = null
  /** 最近一次按下工具栏的时间：手机上点按钮时选区可能先被取消，这段时间内不隐藏工具栏 */
  let barPressedAt = 0

  function hide() {
    selection.show = false
  }

  function read() {
    const sel = window.getSelection()
    const text = sel && !sel.isCollapsed && sel.rangeCount ? sel.toString().trim() : ''
    const root = opts.root()
    const range = text ? sel!.getRangeAt(0) : null
    if (!text || !range || !root || !root.contains(range.commonAncestorContainer)) {
      if (Date.now() - barPressedAt > 800) hide()
      return
    }
    const node = range.startContainer
    const startEl = (node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement) as Element | null
    const paraEl = startEl?.closest('[data-para]') as HTMLElement | null
    const [ci, pi] = (paraEl?.dataset.para ?? '').split('-').map(Number)
    selection.text = text
    selection.chapter = Number.isFinite(ci) ? ci : opts.fallbackChapter()
    selection.para = Number.isFinite(pi) ? pi : -1
    selection.prefix = ''
    if (paraEl) {
      const pre = document.createRange()
      pre.setStart(paraEl, 0)
      pre.setEnd(range.startContainer, range.startOffset)
      selection.prefix = pre.toString()
    }
    const r = range.getBoundingClientRect()
    // 记录选中文字在本章中的位置（长段落也准确），用来计算书签的章内位置
    const seg = opts.chapterEl(selection.chapter)
    if (seg) {
      const sr = seg.getBoundingClientRect()
      selection.paraTop = r.top - sr.top
      selection.segHeight = sr.height
    } else {
      selection.segHeight = 0
    }
    // 工具栏放在系统复制菜单的另一侧，避免重叠：
    // iOS 的菜单在上半屏时显示在选区下方、下半屏时显示在上方，所以我们相反；
    // 其他系统（Android 等）的菜单一般在选区上方，工具栏放在下方。放不下时换到另一侧
    // 按屏幕上可见的那部分选区定位：选中的文字较长时，选区的外框会延伸到屏幕外
    // （左右翻页时后面的栏在屏幕右侧之外），按外框计算工具栏会被推到屏幕边缘
    const v = visibleRect(range, root.getBoundingClientRect()) ?? r
    // 工具栏宽度由 SelectionBar 自己按实际宽度限制在屏幕内，这里只取选区中点
    selection.x = Math.min(window.innerWidth - 8, Math.max(8, v.left + v.width / 2))
    const below = v.bottom + 10
    const above = v.top - 50
    const fitsBelow = v.bottom + 56 < window.innerHeight
    const fitsAbove = above >= 8
    const preferAbove = isIOS && (v.top + v.bottom) / 2 < window.innerHeight / 2
    const y = preferAbove ? (fitsAbove ? above : below) : fitsBelow ? below : above
    // 选区滚出屏幕时工具栏停在屏幕边缘
    selection.y = Math.min(window.innerHeight - 50, Math.max(8, y))
    selection.show = true
  }

  /** 选区变化（拖动选择柄时会连续触发）：稍等再计算 */
  function update() {
    if (timer) clearTimeout(timer)
    timer = setTimeout(read, 200)
  }

  function clear() {
    window.getSelection()?.removeAllRanges()
    hide()
  }

  function markBarPressed() {
    barPressedAt = Date.now()
  }

  /** 当前是否有选中的文字（拖动选择、长按选择时不应翻页） */
  function hasSelectedText() {
    return !!window.getSelection()?.toString().trim()
  }

  onMounted(() => document.addEventListener('selectionchange', update))
  onBeforeUnmount(() => {
    document.removeEventListener('selectionchange', update)
    if (timer) clearTimeout(timer)
  })

  return { selection, hide, clear, update, markBarPressed, hasSelectedText }
}
