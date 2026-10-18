<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NSpin, useMessage } from 'naive-ui'
import { ChevronBackOutline, ChevronForwardOutline } from '@vicons/ionicons5'
import { shelfApi } from '@/api'
import QuickReplaceModal from '@/components/QuickReplaceModal.vue'
import DictModal from '@/components/DictModal.vue'
import ReadAloudPanel from '@/components/ReadAloudPanel.vue'
import ReviewDrawer from '@/components/ReviewDrawer.vue'
import CustomThemeModal from '@/components/CustomThemeModal.vue'
import ChangeSourceModal from '@/components/ChangeSourceModal.vue'
import KeyBindingsModal from '@/components/KeyBindingsModal.vue'
import ChapterBody from '@/components/reader/ChapterBody.vue'
import { isPdfBook, pdfPageOf, primePages } from '@/utils/pdf'
import ReaderMenu from '@/components/reader/ReaderMenu.vue'
import ReaderTocDrawer from '@/components/reader/ReaderTocDrawer.vue'
import ReaderSettingsDrawer from '@/components/reader/ReaderSettingsDrawer.vue'
import AutoReadBar from '@/components/reader/AutoReadBar.vue'
import SelectionBar from '@/components/reader/SelectionBar.vue'
import AddBookmarkModal from '@/components/reader/AddBookmarkModal.vue'
import type { BookmarkDraft, Segment } from '@/components/reader/types'
import { getToken, isNetworkError, proxyImage } from '@/api/http'
import { putLocalProgress } from '@/offline/db'
import { useOfflineStore } from '@/stores/offline'
import type { Book, Bookmark, BookSearchHit, Chapter } from '@/api/types'
import { customThemeKey, customToTheme, fontFamily, readerThemes, useReaderStore, type CustomTheme, type PageAnim } from '@/stores/reader'
import { parseContent, type ContentImage } from '@/utils/content'
import { useScriptEffects } from '@/utils/effects'
import { eventToKey, findAction } from '@/utils/keys'
import { useScrollMode } from '@/composables/useScrollMode'
import { usePagedMode } from '@/composables/usePagedMode'
import { useTextSelection } from '@/composables/useTextSelection'
import { useWakeLock } from '@/composables/useWakeLock'
import { useAutoRead } from '@/composables/useAutoRead'
import { useReadingStats } from '@/composables/useReadingStats'
import { useChapterContent } from '@/composables/useChapterContent'
import { useReaderReviews } from '@/composables/useReaderReviews'
import { useReaderTts } from '@/composables/useReaderTts'
import { useFullscreen } from '@/composables/useFullscreen'
import { copyText } from '@/utils/clipboard'
import { findTextRange, flashRange } from '@/utils/textRange'
import { t } from '@/i18n'

/**
 * 阅读页：滚动模式 / 翻页模式的排版、翻页和进度，以及把各部分组合起来。
 * 菜单、目录、设置等界面在 components/reader/，正文加载、段评、朗读、全屏在 composables/
 */
const route = useRoute()
const router = useRouter()
const message = useMessage()
const readerStore = useReaderStore()
const offline = useOfflineStore()
const cfg = computed(() => readerStore.config)

const bookId = computed(() => Number(route.params.id))
const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const loadingInit = ref(true)
const menuVisible = ref(false)
const showToc = ref(false)
const showSettings = ref(false)
const showChange = ref(false)
const showKeys = ref(false)
const { isFullscreen, toggleFullscreen } = useFullscreen()

const customThemes = computed(() => cfg.value.customThemes ?? [])
/** 当前使用的自定义主题（使用内置主题时为 undefined） */
const currentCustom = computed(() => customThemes.value.find((c) => customThemeKey(c.id) === cfg.value.theme))
const theme = computed(() =>
  currentCustom.value ? customToTheme(currentCustom.value) : readerThemes.find((th) => th.key === cfg.value.theme) ?? readerThemes[0],
)

// ---- 自定义主题的编辑 ----
const showThemeEditor = ref(false)
const editingTheme = ref<CustomTheme | null>(null)
function newTheme() {
  editingTheme.value = null
  showThemeEditor.value = true
}
function editTheme(c: CustomTheme) {
  editingTheme.value = c
  showThemeEditor.value = true
}
function onThemeSaved(c: CustomTheme) {
  const list = [...customThemes.value]
  const i = list.findIndex((x) => x.id === c.id)
  if (i >= 0) list[i] = c
  else list.push(c)
  cfg.value.customThemes = list
  cfg.value.theme = customThemeKey(c.id)
}
/** 夜间 / 日间切换：记住切到夜间前的主题（可能是自定义主题），切回时恢复 */
function toggleNight() {
  if (cfg.value.theme === 'night') {
    const day = cfg.value.dayTheme
    const valid = day && (readerThemes.some((th) => th.key === day) || customThemes.value.some((c) => customThemeKey(c.id) === day))
    cfg.value.theme = valid ? day! : 'paper'
  } else {
    cfg.value.dayTheme = cfg.value.theme
    cfg.value.theme = 'night'
  }
}
function onThemeDeleted(id: string) {
  cfg.value.customThemes = customThemes.value.filter((c) => c.id !== id)
  if (cfg.value.theme === customThemeKey(id)) cfg.value.theme = 'paper'
}
const cssVars = computed(() => ({
  '--r-bg': theme.value.bg,
  '--r-desk': theme.value.desk,
  '--r-text': theme.value.text,
  '--r-panel': theme.value.panel,
  '--r-font-size': `${cfg.value.fontSize}px`,
  '--r-line-height': String(cfg.value.lineHeight),
  '--r-para-gap': `${cfg.value.paragraphSpacing}em`,
  '--r-letter': `${cfg.value.letterSpacing}em`,
  '--r-width': `${cfg.value.width}px`,
  '--r-font': fontFamily(cfg.value.fontFamily),
  '--r-indent': cfg.value.indent ? '2em' : '0',
  '--r-weight': String(cfg.value.fontWeight),
}))

// ---------------- 正文加载、段评 ----------------

const { contentCache, fetchContent, prefetch } = useChapterContent(bookId, chapters)

const { loadReviewSummary, reviewCount, fmtReview, reviewTarget, reviewOpen, openReview } = useReaderReviews({
  book,
  bookId,
  // 段评数字会改变排版，分页模式需要重新计算页数
  onLoaded: async (index) => {
    if (isPaged.value && pageSeg.value?.index === index) {
      await nextTick()
      await measurePages()
    }
  },
})

let switchedToVideo = false
function switchToVideo(index: number) {
  if (switchedToVideo) return
  switchedToVideo = true
  router.replace({ name: "watch", params: { id: bookId.value }, query: { index: String(index) } })
}

async function loadSegment(index: number, refresh = false): Promise<Segment> {
  const ch = chapters.value[index]
  try {
    const c = await fetchContent(index, refresh)
    // 正文实际上是视频地址（聚合书源的短剧等）：改用短剧播放页（服务器已把这本书标记为视频）
    if (c.video) switchToVideo(index)
    const paras = parseContent(c.content)
    if (isPdf.value) {
      const pages = paras.flatMap((p) => (p.type === 'img' ? [pdfPageOf(p.img.display)] : [])).filter((n): n is number => n !== null)
      await primePages(bookId.value, pages)
    } else loadReviewSummary(index)
    return { index, title: c.title || ch?.title || '', paras }
  } catch (e: any) {
    return { index, title: ch?.title || '', paras: [], error: e.message }
  }
}

// ---------------- 当前进度 ----------------

const curIndex = ref(0)
const curPos = ref(0) // 0-10000 章节内进度
const curTitle = computed(() => chapters.value[curIndex.value]?.title ?? '')
const progressPercent = computed(() =>
  chapters.value.length ? (((curIndex.value + curPos.value / 10000) / chapters.value.length) * 100).toFixed(1) : '0',
)

let saveTimer: ReturnType<typeof setTimeout> | null = null
function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveProgress, 1500)
}

function saveProgress(keepalive = false) {
  if (!book.value) return
  const index = curIndex.value
  const pos = Math.round(curPos.value)
  const title = curTitle.value
  if (keepalive) {
    // 页面关闭时：本地记录 + keepalive 请求（离线时请求失败，下次联网再同步）
    const time = Date.now()
    putLocalProgress({ bookId: bookId.value, index, pos, title, time, dirty: true }).catch(() => {})
    const body = JSON.stringify({ index, pos, title, time })
    const url = `/api/shelf/${bookId.value}/progress`
    fetch(url, {
      method: 'PUT',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body,
    }).catch(() => {})
  } else {
    offline.saveProgress(bookId.value, index, pos, title)
  }
}

// ---------------- 翻页方式 ----------------

/** 漫画（图片书源）：固定上下滚动，图片铺满、无间隔 */
/** PDF 本地书的正文是一页页的图片，也按漫画的方式排版 */
const isPdf = computed(() => isPdfBook(book.value))
const isComic = computed(() => !!book.value?.isImage || isPdf.value)
/** 实际使用的翻页方式（漫画固定为上下滚动） */
const mode = computed(() => (isComic.value ? "scroll" : cfg.value.mode))
/** 分页模式：左右翻页 / 上下翻页 */
const isPaged = computed(() => mode.value === "page" || mode.value === "vpage")
const isVertical = computed(() => mode.value === "vpage")

/** 窗口尺寸（判断是否双页） */
const winSize = reactive({ w: window.innerWidth, h: window.innerHeight })
const onWinResize = () => Object.assign(winSize, { w: window.innerWidth, h: window.innerHeight })
window.addEventListener("resize", onWinResize)
onBeforeUnmount(() => window.removeEventListener("resize", onWinResize))
/** 双页显示：左右翻页、横向且足够宽（每页至少约 350px）时，一屏左右两页并排 */
const isDouble = computed(() => mode.value === "page" && cfg.value.doublePage !== "off" && winSize.w >= 960 && winSize.w > winSize.h)
/** 翻页动画（漫画为滚动模式，不涉及） */
const pageAnims: PageAnim[] = ["slide", "cover", "curl", "fade", "none"]
const pageAnim = computed<PageAnim>(() => (pageAnims.includes(cfg.value.pageAnim as PageAnim) ? cfg.value.pageAnim! : "slide"))

// 上下滚动
const {
  scroller, segments, segRefs, setSegRef, loadingMore, openScroll, appendNext, onScroll,
  onTouchStart: onScrollerTouchStart, onTouchEnd: onScrollerTouchEnd, scrollBy, rememberScrollTop,
} = useScrollMode({
  chapters, curIndex, curPos, menuVisible, loadSegment, prefetch, scheduleSave,
  active: () => mode.value === 'scroll',
  isCached: (i) => contentCache.has(i),
  preload: (i) => fetchContent(i).catch(() => {}),
  onScrolled: () => selection.show && textSel.update(),
})

// 左右翻页、上下翻页
const {
  pager, columns, pageClip, pageSeg, page, pageCount, pageLoading, pageMetrics, pageStarts,
  clipStyle, columnStyle, openPage, measurePages, remeasure, onResize, updatePagePos, nextPage, prevPage,
} = usePagedMode({
  chapters, curIndex, curPos, isVertical, isDouble, pageAnim, loadSegment, prefetch, scheduleSave,
  active: () => isPaged.value,
})

// ---------------- 朗读 ----------------

const { tts, ttsEngines, ttsVoices, browserTts, ttsEngineName, ttsStart, ttsStartAt, ttsToggle, ttsStop, ttsSkip } = useReaderTts({
  cfg, chapters, curIndex, isPaged, isVertical, pageSeg, segments, pageClip, scroller, columns,
  page, pageCount, pageStarts, pageMetrics, updatePagePos, openPage, openScroll, appendNext,
  onStart: () => (menuVisible.value = false),
})

// 阅读时屏幕常亮
useWakeLock(() => cfg.value.keepScreenOn !== false)

// ---------------- 通用操作 ----------------

async function goChapter(index: number, pos = 0) {
  if (index < 0 || index >= chapters.value.length) return
  showToc.value = false
  menuVisible.value = false
  if (isPaged.value) await openPage(index, pos)
  else await openScroll(index, pos)
  saveProgress()
}

function nextChapter() {
  goChapter(curIndex.value + 1)
}

function prevChapter() {
  goChapter(curIndex.value - 1)
}

/** 正在刷新本章（刷新按钮转圈、防止重复点击） */
const reloading = ref(false)

async function reloadChapter() {
  if (reloading.value) return
  reloading.value = true
  contentCache.delete(curIndex.value)
  const idx = curIndex.value
  try {
    const res = await fetchContent(idx, true).catch((e) => {
      message.error(t('刷新失败：{0}', [e.message]))
      return null
    })
    await goChapter(idx, curPos.value)
    if (res?.incomplete) message.warning(t('本章仍不完整，书源后续页面获取失败，可稍后再试'))
    else if (res) message.success(t('本章已刷新'), { duration: 1500 })
  } finally {
    reloading.value = false
  }
}

// ---------------- 选中文字：复制、添加书签、添加替换规则、查字典 ----------------

const textSel = useTextSelection({
  root: () => (isPaged.value ? pager.value : scroller.value),
  chapterEl: (i) => (isPaged.value ? null : segRefs.get(i)),
  fallbackChapter: () => curIndex.value,
})
const selection = textSel.selection
const clearSelection = textSel.clear

/** 书签的章内位置：翻页模式为当前页的位置，滚动模式按选中段落在本章中的位置计算 */
function selectionPos(): number {
  if (isPaged.value) return Math.round(curPos.value)
  if (selection.segHeight > 0) return Math.round(Math.max(0, Math.min(1, selection.paraTop / selection.segHeight)) * 10000)
  return selection.chapter === curIndex.value ? Math.round(curPos.value) : 0
}

async function copySelection() {
  const ok = await copyText(selection.text)
  if (ok) message.success(t('已复制'))
  else message.error(t('当前浏览器不允许复制，请使用系统菜单中的“拷贝”'))
  clearSelection()
}

const tocDrawer = ref<InstanceType<typeof ReaderTocDrawer> | null>(null)
/** 待确认的书签：点“书签”后先弹出确认框，可以写备注 */
const bookmarkDraft = ref<BookmarkDraft | null>(null)

function addBookmark() {
  const idx = selection.chapter >= 0 ? selection.chapter : curIndex.value
  bookmarkDraft.value = {
    chapterIndex: idx,
    chapterName: chapters.value[idx]?.title ?? "",
    chapterPos: selectionPos(),
    bookText: selection.text.slice(0, 2000),
    content: "",
  }
  clearSelection()
}

const showReplace = ref(false)
const replaceText = ref("")
function openReplace() {
  replaceText.value = selection.text
  clearSelection()
  showReplace.value = true
}
const showDict = ref(false)
const dictWord = ref("")
/** 本书的正文处理开关（只对有文字的书显示） */
const processing = computed(() =>
  book.value && !isComic.value && !book.value.isAudio && !book.value.isVideo
    ? { reSegment: !!book.value.reSegment, removeSameTitle: book.value.removeSameTitle !== false }
    : null,
)

/** 修改正文处理后清空正文缓存，按新的设置重新显示当前章节 */
async function setProcessing(key: "reSegment" | "removeSameTitle", value: boolean) {
  if (!book.value) return
  try {
    book.value = await shelfApi.update(book.value.id, { [key]: value })
    contentCache.clear()
    await goChapter(curIndex.value, curPos.value)
  } catch (e: any) {
    message.error(e.message)
  }
}

/** 从选中处开始朗读 */
function speakSelection() {
  const { chapter, para, prefix } = selection
  clearSelection()
  ttsStartAt(chapter, para, prefix)
}

function openDict() {
  dictWord.value = selection.text.trim().slice(0, 200)
  clearSelection()
  showDict.value = true
}

/** 添加替换规则后重新显示当前章节（服务器返回正文时应用替换规则） */
async function onReplaceSaved() {
  contentCache.clear()
  await goChapter(curIndex.value, curPos.value)
}

/**
 * 书签记录的是章节序号，换源后章节顺序可能变了：序号对应的章节名不一致时，按章节名找（同名的取离原序号最近的），
 * 完全相同找不到时去掉“第 X 章”前缀再比较；都找不到就仍用原序号
 */
function resolveChapter(index: number, name: string): number {
  if (!name) return index
  const norm = (s: string) => s.replace(/\s+/g, "")
  const bare = (s: string) => norm(s).replace(/^第[0-9零〇一二两三四五六七八九十百千万]+[章节回卷集话篇]/, "")
  if (norm(chapters.value[index]?.title ?? "") === norm(name)) return index
  for (const key of [norm, bare]) {
    const target = key(name)
    if (!target) continue
    let best = -1
    chapters.value.forEach((c, i) => {
      if (key(c.title) === target && (best < 0 || Math.abs(i - index) < Math.abs(best - index))) best = i
    })
    if (best >= 0) return best
  }
  return index
}

/** 跳到第 index 章的 pos 处，并找到文字高亮（书签、书内搜索结果） */
async function jumpToText(index: number, pos: number, text: string) {
  await goChapter(index, pos)
  if (!text) return
  // 等排版（翻页模式的分页）完成后再查找文字
  await nextTick()
  setTimeout(() => revealText(index, text, pos), 150)
}

function jumpBookmark(b: Bookmark) {
  jumpToText(resolveChapter(b.chapterIndex, b.chapterName), b.chapterPos, b.bookText)
}

function jumpSearchHit(h: BookSearchHit, keyword: string) {
  jumpToText(h.chapterIndex, h.pos, keyword)
}

/** 把第 index 章中的文字移到可见位置，并短暂高亮（书签、书内搜索结果） */
function revealText(index: number, text: string, pos: number) {
  const root = isPaged.value ? columns.value : segRefs.get(index)
  if (!root) return
  const range = findTextRange(root, text, pos)
  if (!range) return
  const r = range.getBoundingClientRect()
  if (!isPaged.value) {
    const s = scroller.value
    if (s && (r.top < 60 || r.bottom > s.clientHeight - 60)) s.scrollTop += r.top - s.clientHeight / 3
  } else {
    const cr = root.getBoundingClientRect()
    let target = page.value
    if (isVertical.value) {
      const y = r.top - cr.top
      target = Math.max(0, pageStarts.value.findLastIndex((st) => st <= y + 1))
    } else {
      target = Math.floor((r.left - cr.left) / (pageMetrics.width + pageMetrics.gap))
    }
    target = Math.min(pageCount.value - 1, Math.max(0, target))
    if (target !== page.value) {
      page.value = target
      updatePagePos()
    }
  }
  flashRange(range)
}

// ---------------- 点击、滚轮、滑动、快捷键 ----------------

function onTap(e: MouseEvent) {
  // 可点击的图片（段评气泡等）自己处理点击；普通图片（如漫画）与点击文字一样翻页 / 呼出菜单
  if ((e.target as HTMLElement).closest('a,button,img.clickable,.sel-bar,.auto-bar')) return
  // 选中文字后的点击只用来取消选择，不翻页；拖动选择文字结束时也会触发点击
  if (selection.show) {
    if (!textSel.hasSelectedText()) textSel.hide()
    return
  }
  if (textSel.hasSelectedText()) return
  if (Date.now() - lastSwipe < 400) return // 滑动翻页后浏览器可能还会触发一次点击
  const w = window.innerWidth
  const hgt = window.innerHeight
  const x = e.clientX
  const y = e.clientY
  if (menuVisible.value) {
    menuVisible.value = false
    return
  }
  // 上下翻页、上下滚动：正中间呼出菜单，其余位置（包括正文框外的空白）按上下半屏区分，上半屏上一页、下半屏下一页
  if (isVertical.value || !isPaged.value) {
    if (x > w * 0.3 && x < w * 0.7 && y > hgt * 0.3 && y < hgt * 0.7) {
      rememberScrollTop()
      menuVisible.value = true
      return
    }
    const forward = y >= hgt * 0.5 || cfg.value.tapNextOnLeft
    if (isPaged.value) forward ? nextPage() : prevPage()
    else scrollBy(forward ? 1 : -1)
    return
  }
  // 左右翻页：中间一列呼出菜单，左侧上一页、右侧下一页
  if (x > w * 0.3 && x < w * 0.7) {
    menuVisible.value = true
    return
  }
  if (x >= w * 0.7 || cfg.value.tapNextOnLeft) nextPage()
  else prevPage()
}

// 分页模式：滚轮、滑动翻页
let lastWheel = 0
function onWheel(e: WheelEvent) {
  if (!isPaged.value || Math.abs(e.deltaY) < 10 || Date.now() - lastWheel < 350) return
  lastWheel = Date.now()
  e.deltaY > 0 ? nextPage() : prevPage()
}

let touchStart: { x: number; y: number } | null = null
let lastSwipe = 0
function onTouchStart(e: TouchEvent) {
  const p = e.touches[0]
  touchStart = p ? { x: p.clientX, y: p.clientY } : null
}
function onTouchEnd(e: TouchEvent) {
  const p = e.changedTouches[0]
  if (!touchStart || !p || !isPaged.value) return
  // 长按拖动选择文字时不翻页
  if (textSel.hasSelectedText()) {
    touchStart = null
    return
  }
  const dx = p.clientX - touchStart.x
  const dy = p.clientY - touchStart.y
  touchStart = null
  const [main, cross] = isVertical.value ? [dy, dx] : [dx, dy]
  if (Math.abs(main) < 50 || Math.abs(main) < Math.abs(cross)) return
  lastSwipe = Date.now()
  if (menuVisible.value) menuVisible.value = false
  main < 0 ? nextPage() : prevPage()
}

function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null
  if (target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable) return
  // 弹窗打开时不翻页
  if (showKeys.value || showDict.value || showReplace.value || bookmarkDraft.value) return
  if (e.key === "Escape") {
    menuVisible.value = false
    return
  }
  const key = eventToKey(e)
  if (!key) return
  const action = findAction(cfg.value.keys, key)
  if (!action) return
  // 上下滚动模式：↑↓ 保持浏览器原本的逐行滚动
  if (!isPaged.value && (key === "ArrowUp" || key === "ArrowDown") && (action === "nextPage" || action === "prevPage")) return
  e.preventDefault()
  switch (action) {
    case "nextPage":
      isPaged.value ? nextPage() : scrollBy(1)
      break
    case "prevPage":
      isPaged.value ? prevPage() : scrollBy(-1)
      break
    case "nextChapter":
      nextChapter()
      break
    case "prevChapter":
      prevChapter()
      break
    case "toc":
      showToc.value = !showToc.value
      break
    case "fullscreen":
      toggleFullscreen()
      break
  }
}

// ---------------- 排版变化与初始化 ----------------

function onBeforeUnload() {
  saveProgress(true)
}

// 修改排版后重新计算
watch(
  () => [cfg.value.fontSize, cfg.value.lineHeight, cfg.value.paragraphSpacing, cfg.value.letterSpacing,
    cfg.value.fontFamily, cfg.value.width, cfg.value.indent, cfg.value.fontWeight],
  async () => {
    if (isPaged.value) {
      await nextTick()
      await remeasure()
    }
  },
)

watch(mode, () => {
  if (!loadingInit.value && chapters.value.length) goChapter(curIndex.value, curPos.value)
})

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', onResize)
  window.addEventListener('beforeunload', onBeforeUnload)
  readerStore.loadRemote()
  try {
    let b: Book
    let c: Chapter[]
    let start: { index: number; pos: number }
    try {
      ;[b, c] = await Promise.all([shelfApi.get(bookId.value), shelfApi.chapters(bookId.value)])
      await offline.refreshBook(b, c)
      // 本地有更新的进度（离线时读的）就用本地的，并同步到服务器
      start = await offline.resolveProgress(b)
      if (start.index !== b.durChapterIndex || start.pos !== b.durChapterPos) offline.syncProgress()
    } catch (e) {
      offline.markError(e)
      const local = isNetworkError(e) ? await offline.loadOfflineBook(bookId.value) : null
      if (!local) throw isNetworkError(e) ? new Error(t('当前离线，这本书还没有离线下载')) : e
      ;({ book: b, chapters: c } = local)
      start = { index: b.durChapterIndex, pos: b.durChapterPos }
      message.info(t('当前离线，正在阅读已下载的章节'))
    }
    if (b.isAudio || b.isVideo) {
      // 音频用播放器、视频（短剧）用竖屏播放页打开
      router.replace({ name: b.isVideo ? "watch" : "listen", params: { id: b.id }, query: route.query })
      return
    }
    book.value = b
    chapters.value = c
    document.title = t('{0} - 阅读', [b.name])
    if (!c.length) {
      message.warning(t('目录为空，请尝试更新目录或换源'))
      loadingInit.value = false
      return
    }
    const qIndex = route.query.index !== undefined ? Number(route.query.index) : null
    const index = Math.min(c.length - 1, Math.max(0, qIndex ?? start.index))
    const pos = qIndex !== null && qIndex !== start.index ? 0 : start.pos
    loadingInit.value = false
    await nextTick()
    await goChapter(index, pos)
  } catch (e: any) {
    loadingInit.value = false
    message.error(e.message)
  }
})

onBeforeUnmount(() => {
  saveProgress()
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('beforeunload', onBeforeUnload)
})

// ---------------- 正文图片 ----------------

// 正文图片点击：段评气泡、本章说卡片等（执行图片参数中的书源脚本，评论页以底部弹窗显示）
const effects = useScriptEffects()
const clickingImg = ref(false)
/** 图片点击：只有可点击的图片拦截点击，其余交给翻页 / 菜单 */
function onImgTap(e: MouseEvent, index: number, img: ContentImage) {
  if (!img.clickable) return
  e.stopPropagation()
  onImgClick(index, img)
}

async function onImgClick(index: number, img: ContentImage) {
  if (!img.clickable || clickingImg.value) return
  clickingImg.value = true
  try {
    const r = await shelfApi.clickImage(bookId.value, index, img.src)
    await effects.apply(r)
    // 脚本错误会附带大段源码上下文，只显示第一行
    if (!r.ok && r.message) message.error(String(r.message).split(/\n| Source context/)[0])
  } catch (e: any) {
    message.error(isNetworkError(e) ? t('当前离线，无法打开评论') : e.message)
  } finally {
    clickingImg.value = false
  }
}

function imgSrc(src: string) {
  return proxyImage(src, { bookId: bookId.value, sourceId: book.value?.sourceId })
}

// ---------------- 自动阅读 ----------------

/** 用户点了暂停（与菜单、弹窗打开时的自动暂停分开） */
const autoPaused = ref(false)
const autoRead = useAutoRead({
  isPaged: () => isPaged.value,
  scroller: () => scroller.value,
  speed: () => cfg.value.autoScrollSpeed ?? 40,
  seconds: () => cfg.value.autoPageSeconds ?? 15,
  nextPage: () => nextPage(),
  atEnd: () => {
    if (isPaged.value) return curIndex.value >= chapters.value.length - 1 && page.value >= pageCount.value - 1
    const s = scroller.value
    const last = segments.value[segments.value.length - 1]
    return !!s && !!last && last.index >= chapters.value.length - 1 && !last.loading && s.scrollTop + s.clientHeight >= s.scrollHeight - 2
  },
  blocked: () =>
    autoPaused.value || menuVisible.value || showToc.value || showSettings.value || showChange.value || showKeys.value ||
    !!bookmarkDraft.value || showReplace.value || showDict.value || selection.show || tts.active || loadingInit.value || pageLoading.value ||
    document.visibilityState !== "visible",
  onEnd: () => message.info(t('已经读到最后了，自动阅读已停止')),
})
function startAutoRead() {
  autoPaused.value = false
  showSettings.value = false
  menuVisible.value = false
  autoRead.start()
}
function stopAutoRead() {
  autoRead.stop()
  autoPaused.value = false
}
/** 调整速度：滚动模式改每秒滚动的像素，翻页模式改每页停留的秒数 */
function autoFaster(faster: boolean) {
  if (isPaged.value) {
    const v = (cfg.value.autoPageSeconds ?? 15) + (faster ? -1 : 1)
    cfg.value.autoPageSeconds = Math.min(60, Math.max(3, v))
  } else {
    const v = (cfg.value.autoScrollSpeed ?? 40) + (faster ? 5 : -5)
    cfg.value.autoScrollSpeed = Math.min(200, Math.max(10, v))
  }
}
const autoLabel = computed(() => (isPaged.value ? t('{0} 秒/页', [cfg.value.autoPageSeconds ?? 15]) : t('{0} 像素/秒', [cfg.value.autoScrollSpeed ?? 40])))
/** 翻页模式：本页停留时间的进度（0-100） */
const autoProgress = computed(() =>
  isPaged.value ? Math.min(100, (autoRead.elapsed.value / ((cfg.value.autoPageSeconds ?? 15) * 1000)) * 100) : null,
)
// 手动翻页后重新计时；开始朗读时停止自动阅读
watch([page, curIndex], () => autoRead.resetTimer())
watch(() => tts.active, (a) => a && stopAutoRead())

// ---------------- 阅读统计 ----------------

const stats = useReadingStats({
  bookId: () => bookId.value,
  passiveReading: () => tts.active || (autoRead.running.value && !autoPaused.value),
})
/** 章节字数（不含标签和空白） */
function chapterChars(index: number): number {
  const c = contentCache.get(index)
  return c ? c.content.replace(/<[^>]*>/g, "").replace(/\s+/g, "").length : 0
}
// 读到下一章时，上一章的字数计入阅读字数
watch(curIndex, (n, o) => {
  if (loadingInit.value || o == null || n !== o + 1) return
  stats.addChars(chapterChars(o))
})

function onChanged(nb: Book) {
  book.value = nb
  contentCache.clear()
  shelfApi.chapters(nb.id).then(async (c) => {
    chapters.value = c
    // 换源后先清掉旧的离线正文，再加载章节
    await offline.refreshBook(nb, c)
    goChapter(Math.min(nb.durChapterIndex, c.length - 1), 0)
  })
}
</script>

<template>
  <!-- 翻页模式下正文框之外的空白区域（宽屏时左右两侧）：点击、滚轮、滑动与正文框内相同 -->
  <div
    class="reader"
    :style="cssVars"
    :class="[`mode-${mode}`, { comic: isComic }]"
    @click.self="isPaged && onTap($event)"
    @wheel.self.passive="onWheel"
    @touchstart.self.passive="onTouchStart"
    @touchend.self.passive="onTouchEnd"
  >
    <div v-if="loadingInit" class="center"><n-spin /></div>

    <!-- 滚动模式 -->
    <div v-if="mode === 'scroll' && !loadingInit" ref="scroller" class="scroller" @scroll.passive="onScroll" @touchstart.passive="onScrollerTouchStart" @touchend.passive="onScrollerTouchEnd" @touchcancel.passive="onScrollerTouchEnd" @click="onTap">
      <div class="paper">
        <article v-for="seg in segments" :key="seg.index" :ref="(el) => setSegRef(seg.index, el)" class="chapter">
          <chapter-body
            :seg="seg"
            :review-count="reviewCount"
            :fmt-review="fmtReview"
            :img-src="imgSrc"
            :pdf-book-id="isPdf ? bookId : undefined"
            scroll-mode
            @review="(pn: number, text?: string) => openReview(seg.index, pn, text)"
            @img-tap="(e: MouseEvent, img: ContentImage) => onImgTap(e, seg.index, img)"
            @reload="reloadChapter"
            @change-source="showChange = true"
          />
        </article>
        <div class="chapter-end muted-r">
          <n-spin v-if="loadingMore" size="small" />
          <span v-else-if="segments.length && segments[segments.length - 1].index >= chapters.length - 1">{{ t('— 全书完 —') }}</span>
        </div>
      </div>
    </div>

    <!-- 翻页模式 -->
    <div
      v-if="isPaged && !loadingInit"
      ref="pager"
      class="pager"
      :class="{ vertical: isVertical, double: isDouble }"
      @click="onTap"
      @wheel.passive="onWheel"
      @touchstart.passive="onTouchStart"
      @touchend.passive="onTouchEnd"
    >
      <div v-if="pageLoading" class="center"><n-spin /></div>
      <div v-show="!pageLoading" ref="pageClip" class="page-clip" :style="clipStyle">
        <div ref="columns" class="columns" :style="columnStyle">
          <chapter-body
            v-if="pageSeg"
            :seg="pageSeg"
            :review-count="reviewCount"
            :fmt-review="fmtReview"
            :img-src="imgSrc"
            :pdf-book-id="isPdf ? bookId : undefined"
            @review="(pn: number, text?: string) => openReview(pageSeg!.index, pn, text)"
            @img-tap="(e: MouseEvent, img: ContentImage) => onImgTap(e, pageSeg!.index, img)"
            @img-load="measurePages"
            @reload="reloadChapter"
          />
        </div>
      </div>
      <div class="page-foot muted-r">
        <span class="ellipsis">{{ curTitle }}</span>
        <span>{{ page + 1 }} / {{ pageCount }}</span>
      </div>
    </div>

    <reader-menu
      :visible="menuVisible"
      :book="book"
      :chapters="chapters"
      :cur-index="curIndex"
      :cur-title="curTitle"
      :progress-percent="progressPercent"
      :reloading="reloading"
      :is-comic="isComic"
      :is-fullscreen="isFullscreen"
      :is-night="cfg.theme === 'night'"
      :theme-bg="theme.bg"
      @back="router.back()"
      @reload="reloadChapter"
      @change-source="showChange = true"
      @info="router.push({ name: 'book', params: { id: bookId } })"
      @prev-chapter="prevChapter"
      @next-chapter="nextChapter"
      @go-chapter="goChapter"
      @toc="showToc = true"
      @settings="showSettings = true"
      @read-aloud="tts.active ? (menuVisible = false) : ttsStart()"
      @fullscreen="toggleFullscreen"
      @night="toggleNight"
    />

    <reader-toc-drawer
      ref="tocDrawer"
      v-model:show="showToc"
      :book-id="bookId"
      :is-local="!!book?.isLocal"
      :chapters="chapters"
      :cur-index="curIndex"
      @go="goChapter"
      @jump-bookmark="jumpBookmark"
      @jump-search="jumpSearchHit"
    />

    <reader-settings-drawer
      v-model:show="showSettings"
      :is-comic="isComic"
      :is-paged="isPaged"
      :page-anim="pageAnim"
      :auto-running="autoRead.running.value"
      :auto-label="autoLabel"
      :processing="processing"
      @process="setProcessing"
      @start-auto="startAutoRead"
      @stop-auto="stopAutoRead"
      @keys="showKeys = true"
      @new-theme="newTheme"
      @edit-theme="editTheme"
    />

    <!-- 侧边翻章按钮（桌面端） -->
    <div v-if="mode === 'scroll'" class="side-nav">
      <button :title="t('上一章 [')" @click="prevChapter"><n-icon :component="ChevronBackOutline" /></button>
      <button :title="t('下一章 ]')" @click="nextChapter"><n-icon :component="ChevronForwardOutline" /></button>
    </div>

    <change-source-modal v-model:show="showChange" :book="book" @changed="onChanged" />
    <read-aloud-panel
      v-if="tts.active"
      :state="tts.state"
      :engine="cfg.ttsEngine || 'browser'"
      :rate="cfg.ttsRate || 1"
      :voice="cfg.ttsVoice || ''"
      :engines="ttsEngines"
      :voices="ttsVoices"
      :browser-supported="browserTts"
      :text="tts.text"
      :error="tts.error"
      :bg="theme.panel"
      :fg="theme.text"
      :title="ttsEngineName"
      @toggle="ttsToggle"
      @stop="ttsStop"
      @prev="ttsSkip(-1)"
      @next="ttsSkip(1)"
      @update:engine="(v: string) => (readerStore.config.ttsEngine = v)"
      @update:rate="(v: number) => (readerStore.config.ttsRate = v)"
      @update:voice="(v: string) => (readerStore.config.ttsVoice = v)"
    />

    <review-drawer
      v-if="reviewTarget"
      v-model:show="reviewOpen"
      :book-id="bookId"
      :chapter-index="reviewTarget.chapterIndex"
      :para="reviewTarget.para"
      :para-data="reviewTarget.paraData"
      :total="reviewTarget.total"
      :excerpt="reviewTarget.excerpt"
      :theme="theme"
    />
    <key-bindings-modal v-model:show="showKeys" v-model:value="cfg.keys" />

    <auto-read-bar
      v-if="autoRead.running.value && !menuVisible"
      :paused="autoPaused"
      :label="autoLabel"
      :progress="autoProgress"
      @toggle-pause="autoPaused = !autoPaused"
      @slower="autoFaster(false)"
      @faster="autoFaster(true)"
      @stop="stopAutoRead"
    />

    <selection-bar
      v-if="selection.show"
      :x="selection.x"
      :y="selection.y"
      @press="textSel.markBarPressed()"
      @copy="copySelection"
      @bookmark="addBookmark"
      @replace="openReplace"
      @dict="openDict"
      @speak="speakSelection"
    />
    <add-bookmark-modal v-model:draft="bookmarkDraft" :book-id="bookId" @saved="tocDrawer?.reloadBookmarks()" />
    <custom-theme-modal v-model:show="showThemeEditor" :theme="editingTheme" @saved="onThemeSaved" @deleted="onThemeDeleted" />
    <dict-modal v-model:show="showDict" :word="dictWord" />
    <quick-replace-modal
      v-model:show="showReplace"
      :text="replaceText"
      :book-name="book?.name ?? ''"
      :origin="book?.origin ?? ''"
      :origin-name="book?.originName ?? ''"
      @saved="onReplaceSaved"
    />
  </div>
</template>

<style>
/* 跳转到书签、搜索结果时短暂高亮文字（CSS Custom Highlight API，不改动正文 DOM） */
::highlight(text-flash) {
  background-color: rgba(255, 190, 0, 0.45);
}
</style>

<style scoped>
.reader {
  position: fixed;
  inset: 0;
  /* 纸张外的底色；正文在居中的“纸张”盒子里 */
  background: var(--r-desk);
  color: var(--r-text);
  transition: background 0.2s, color 0.2s;
}

.center {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.scroller {
  height: 100%;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* 纸张：正文宽 var(--r-width)，左右留白 65px */
.paper {
  max-width: calc(var(--r-width) + 130px);
  min-height: 100%;
  margin: 0 auto;
  padding: 0 65px;
  background: var(--r-bg);
  box-shadow: 0 0 24px rgba(0, 0, 0, 0.08);
  transition: background 0.2s;
}

.chapter {
  padding: 32px 0 48px;
  font-family: var(--r-font);
  font-size: var(--r-font-size);
  line-height: var(--r-line-height);
  letter-spacing: var(--r-letter);
  font-weight: var(--r-weight);
}

/* 漫画：图片铺满纸张宽度，上下无间隔，章节名只占一行 */
.reader.comic .paper {
  padding: 0;
  max-width: min(100%, 860px);
}

.reader.comic .chapter {
  padding: 0 0 6px;
}

.reader.comic :deep(.chapter-title) {
  font-size: 13px;
  font-weight: 500;
  opacity: 0.6;
  margin: 0;
  padding: 10px 14px;
}

.reader.comic :deep(.para-img) {
  display: block;
  width: 100%;
  max-width: 100%;
  max-height: none;
  margin: 0;
  border-radius: 0;
  min-height: 120px;
}

.reader.comic :deep(.para) {
  padding: 0 14px;
  font-size: 14px;
}

.chapter-end {
  text-align: center;
  padding: 24px 0 120px;
  font-size: 13px;
}

.muted-r {
  opacity: 0.6;
}

/* 翻页 */
.pager {
  position: absolute;
  inset: 0;
  max-width: calc(var(--r-width) + 130px);
  margin: 0 auto;
  padding: 36px 65px 40px;
  overflow: hidden;
  background: var(--r-bg);
  box-shadow: 0 0 24px rgba(0, 0, 0, 0.08);
  transition: background 0.2s;
}

/* 双页：两页正文 + 中间的间距（与左右内边距之和相同）+ 左右内边距 */
.pager.double {
  max-width: calc(var(--r-width) * 2 + 260px);
}

/* 双页中间的分隔线（正好在两栏间距的中间） */
.pager.double::after {
  content: "";
  position: absolute;
  top: 36px;
  bottom: 40px;
  left: 50%;
  width: 1px;
  background: currentColor;
  opacity: 0.08;
  pointer-events: none;
}

.columns {
  height: 100%;
  font-family: var(--r-font);
  font-size: var(--r-font-size);
  line-height: var(--r-line-height);
  letter-spacing: var(--r-letter);
  font-weight: var(--r-weight);
  column-fill: auto;
  transition: transform 0.25s ease;
}

.page-clip {
  height: 100%;
  overflow: hidden;
}

/* 跨章翻页时的旧页面快照 */
.page-ghost {
  pointer-events: none;
  z-index: 2;
}

/* 覆盖、仿真、淡入淡出：整页（含页边距）的快照，不透明，遮住下面的页面 */
.page-sheet {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: var(--r-bg);
  pointer-events: none;
}

.page-sheet.under {
  z-index: 1;
}

.page-sheet.top {
  z-index: 3;
}

/* 覆盖：在上层移动的页面边缘带阴影 */
.page-sheet.shadow {
  box-shadow: 0 0 22px rgba(0, 0, 0, 0.22);
}

/* 仿真：翻起来的纸张背面，阴影跟随折起部分的形状 */
.page-flap-wrap {
  position: absolute;
  inset: 0;
  z-index: 4;
  pointer-events: none;
  filter: drop-shadow(0 0 10px rgba(0, 0, 0, 0.28));
}

.page-flap {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
  background-color: var(--r-bg);
}

/* 上下翻页：单列排版，按页整体上移 */
.pager.vertical .columns {
  height: auto;
  column-fill: initial;
  /* 不让标题的上边距穿出容器：否则内容整体下移，按行计算的分页会切掉每页最后一行 */
  display: flow-root;
}

.columns :deep(.para-img) {
  max-height: 80vh;
  break-inside: avoid;
}

/* 图片不超过一页的高度，避免被截断 */
.pager.vertical .columns :deep(.para-img) {
  max-height: min(80vh, calc(var(--page-h, 80vh) - 1.5em));
  object-fit: contain;
}

.page-foot {
  position: absolute;
  left: 65px;
  right: 65px;
  bottom: 12px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 12px;
}

.side-nav {
  position: fixed;
  right: 18px;
  bottom: 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 5;
}

.side-nav button {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid rgba(128, 128, 128, 0.25);
  background: var(--r-panel);
  color: var(--r-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s;
}

.side-nav button:hover {
  opacity: 1;
}

/* 窄屏不显示纸张效果，正文铺满 */
@media (max-width: 799px) {
  .paper {
    max-width: none;
    padding: 0 20px;
    box-shadow: none;
  }

  .pager {
    max-width: none;
    padding: 32px 20px 40px;
    box-shadow: none;
  }

  .page-foot {
    left: 20px;
    right: 20px;
  }
}

@media (max-width: 767px) {
  .paper {
    padding: 0 16px;
  }

  .chapter {
    padding-top: 20px;
  }

  .side-nav {
    display: none;
  }

  .pager {
    padding: 28px 18px 36px;
  }

  .page-foot {
    left: 18px;
    right: 18px;
  }
}
</style>
