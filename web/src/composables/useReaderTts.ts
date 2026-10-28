import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import { ttsApi } from '@/api'
import type { Chapter, TtsEngine } from '@/api/types'
import type { ReaderConfig } from '@/stores/reader'
import type { Paragraph } from '@/utils/content'
import {
  BrowserSpeaker, CancelError, HttpSpeaker, browserTtsSupported, loadVoices, pickVoice, splitSentences, type Speaker,
} from '@/utils/readAloud'
import type { Segment } from '@/components/reader/types'
import { t } from '@/i18n'

/** 朗读需要用到的阅读页状态和操作 */
export interface ReaderTtsDeps {
  cfg: Readonly<Ref<ReaderConfig>>
  chapters: Ref<Chapter[]>
  curIndex: Ref<number>
  isPaged: Readonly<Ref<boolean>>
  isVertical: Readonly<Ref<boolean>>
  /** 翻页模式的当前章 / 滚动模式已显示的各章 */
  pageSeg: Ref<Segment | null>
  segments: Ref<Segment[]>
  pageClip: Ref<HTMLElement | null>
  scroller: Ref<HTMLElement | null>
  columns: Ref<HTMLElement | null>
  /** 翻页模式的分页信息 */
  page: Ref<number>
  pageCount: Ref<number>
  pageStarts: Ref<number[]>
  pageMetrics: { width: number; gap: number }
  updatePagePos: () => void
  openPage: (index: number, pos: number) => Promise<void>
  openScroll: (index: number, pos: number) => Promise<void>
  appendNext: () => Promise<void>
  /** 开始朗读时（收起菜单） */
  onStart: () => void
  /** 系统媒体控制（锁屏、通知栏、耳机按键）显示的书名和封面 */
  bookName: () => string
  coverUrl: () => string | undefined
}

/**
 * 朗读：按段落朗读（长段落再按句子切分），读到哪里高亮哪里并自动滚动 / 翻页，读完一章自动进入下一章
 */
export function useReaderTts(d: ReaderTtsDeps) {
  const message = useMessage()
  const tts = reactive({
    active: false,
    state: "loading" as "playing" | "paused" | "loading",
    /** 正在读的章节、段落（-1 为章节名）、段内第几句 */
    chapter: -1,
    para: 0,
    chunk: 0,
    text: "",
    error: "",
  })
  const ttsEngines = ref<TtsEngine[]>([])
  const ttsVoices = ref<SpeechSynthesisVoice[]>([])
  const browserTts = browserTtsSupported()
  let speaker: Speaker | null = null
  /** 每次开始 / 跳转时递增，旧的朗读循环发现编号变化后退出 */
  let ttsRun = 0
  /** 朗读自己切换章节时为 true，不当作用户操作 */
  let ttsJumping = false
  let ttsActiveEl: HTMLElement | null = null
  /** 从选中处开始朗读：第一句跳过前面这么多字 */
  let trimNext = 0

  function createSpeaker(): Speaker {
    const engine = d.cfg.value.ttsEngine || "browser"
    const rate = d.cfg.value.ttsRate || 1
    if (engine.startsWith("http:")) {
      const id = Number(engine.slice(5))
      if (ttsEngines.value.some((e) => e.id === id)) return new HttpSpeaker(id, rate)
    }
    if (!browserTts) throw new Error(t('当前浏览器不支持语音朗读，请在“我的 → 朗读引擎”中导入在线朗读引擎'))
    return new BrowserSpeaker(pickVoice(ttsVoices.value, d.cfg.value.ttsVoice), rate)
  }

  const ttsEngineName = computed(() => {
    const e = d.cfg.value.ttsEngine || "browser"
    return e.startsWith("http:") ? ttsEngines.value.find((x) => `http:${x.id}` === e)?.name ?? t('浏览器语音') : t('浏览器语音')
  })

  function ttsParas(index: number): Paragraph[] | null {
    if (d.isPaged.value) return d.pageSeg.value?.index === index && !d.pageSeg.value.loading ? d.pageSeg.value.paras : null
    const seg = d.segments.value.find((s) => s.index === index)
    return seg && !seg.loading ? seg.paras : null
  }

  const contentRoot = () => (d.isPaged.value ? d.pageClip.value : d.scroller.value)

  function paraEl(index: number, i: number): HTMLElement | null {
    return contentRoot()?.querySelector(`[data-para="${index}-${i}"]`) ?? null
  }

  /** 当前屏幕上第一个可见的段落 */
  function firstVisiblePara(index: number): number {
    const paras = ttsParas(index)
    const box = contentRoot()?.getBoundingClientRect()
    if (!paras || !box) return 0
    for (let i = 0; i < paras.length; i++) {
      if (paras[i].type !== "text") continue
      const el = paraEl(index, i)
      if (!el) continue
      for (const r of Array.from(el.getClientRects())) {
        if (r.bottom > box.top + 4 && r.top < box.bottom - 4 && r.right > box.left + 1 && r.left < box.right - 1) return i
      }
    }
    return 0
  }

  /**
   * 段落从哪一句开始读：段落开头在前一页（或已滚出屏幕）时，从屏幕上第一行对应的句子开始，
   * 而不是跳回段落开头
   */
  function firstVisibleChunk(index: number, i: number): number {
    const paras = ttsParas(index)
    const p = paras?.[i]
    const el = paraEl(index, i)
    const box = contentRoot()?.getBoundingClientRect()
    if (!p || p.type !== "text" || !el || !box) return 0
    const rects = Array.from(el.getClientRects()).filter((r) => r.height > 0)
    const total = rects.reduce((a, r) => a + r.height, 0)
    if (!total) return 0
    const horizontal = d.isPaged.value && !d.isVertical.value
    // 已经翻过 / 滚过的部分所占的比例
    let before = 0
    for (const r of rects) {
      if (horizontal ? r.right <= box.left + 1 : r.bottom <= box.top + 1) before += r.height
      else if (!horizontal && r.top < box.top) before += box.top - r.top
    }
    const fraction = before / total
    if (fraction <= 0.02) return 0
    const chunks = splitSentences(p.text)
    const len = chunks.reduce((a, c) => a + c.length, 0)
    let acc = 0
    for (let k = 0; k < chunks.length; k++) {
      if ((acc + chunks[k].length / 2) / len >= fraction) return k
      acc += chunks[k].length
    }
    return chunks.length - 1
  }

  /** 高亮正在读的段落，并滚动 / 翻页到正在读的位置（fraction 为段内已读的比例） */
  function ttsFollow(index: number, i: number, fraction: number) {
    const el = i >= 0 ? paraEl(index, i) : null
    if (el !== ttsActiveEl) {
      ttsActiveEl?.classList.remove("tts-active")
      el?.classList.add("tts-active")
      ttsActiveEl = el
    }
    if (!el) return
    if (!d.isPaged.value) {
      const s = d.scroller.value
      if (!s) return
      const box = s.getBoundingClientRect()
      const r = el.getBoundingClientRect()
      // 长段落按已读比例估算正在读的那一行
      const y = r.top + r.height * fraction
      if (y < box.top + 60 || y > box.bottom - box.height * 0.35) {
        s.scrollTo({ top: s.scrollTop + y - box.top - box.height * 0.25, behavior: "smooth" })
      }
      return
    }
    const cols = d.columns.value
    const rects = Array.from(el.getClientRects()).filter((r) => r.height > 0)
    if (!cols || !rects.length) return
    // 段落可能跨页：按已读比例找到对应的那一部分
    const total = rects.reduce((a, r) => a + r.height, 0)
    let acc = 0
    let target = rects[0]
    for (const r of rects) {
      acc += r.height
      target = r
      if (acc >= total * fraction) break
    }
    const c = cols.getBoundingClientRect()
    let p = 0
    if (d.isVertical.value) {
      const top = target.top - c.top
      d.pageStarts.value.forEach((s, k) => {
        if (s <= top + 1) p = k
      })
    } else {
      p = Math.floor((target.left - c.left + 1) / (d.pageMetrics.width + d.pageMetrics.gap))
    }
    p = Math.max(0, Math.min(d.pageCount.value - 1, p))
    if (p !== d.page.value) {
      d.page.value = p
      d.updatePagePos()
    }
  }

  const sleepMs = (ms: number) => new Promise((r) => setTimeout(r, ms))

  /** 朗读切换到第 index 章（确保已加载） */
  async function ttsGo(index: number) {
    ttsJumping = true
    try {
      if (d.isPaged.value) {
        await d.openPage(index, 0)
      } else {
        const has = () => d.segments.value.some((s) => s.index === index && !s.loading)
        if (!has() && d.segments.value[d.segments.value.length - 1]?.index === index - 1) {
          for (let k = 0; k < 100 && !has(); k++) {
            await d.appendNext()
            if (!has()) await sleepMs(100)
          }
        }
        if (!has()) await d.openScroll(index, 0)
      }
      await nextTick()
    } finally {
      setTimeout(() => (ttsJumping = false), 400)
    }
  }

  /** 后面要读的几句（在线引擎预先请求） */
  function upcoming(paras: Paragraph[], para: number, chunk: number, chunks: string[]): string[] {
    const out = chunks.slice(chunk + 1, chunk + 3)
    for (let i = para + 1; i < paras.length && out.length < 2; i++) {
      const p = paras[i]
      if (p.type === "text") out.push(...splitSentences(p.text).slice(0, 2 - out.length))
    }
    return out
  }

  async function ttsLoop(run: number) {
    while (run === ttsRun && speaker) {
      const index = tts.chapter
      let paras = ttsParas(index)
      if (!paras) {
        await ttsGo(index)
        if (run !== ttsRun) return
        paras = ttsParas(index)
        if (!paras) {
          tts.error = t('章节加载失败')
          tts.state = "paused"
          return
        }
      }
      if (tts.para >= paras.length) {
        const next = index + 1
        if (next >= d.chapters.value.length) {
          message.info(t('已经读完最后一章'))
          ttsStop()
          return
        }
        await ttsGo(next)
        if (run !== ttsRun) return
        tts.chapter = next
        tts.para = -1
        tts.chunk = 0
        continue
      }
      let chunks: string[]
      if (tts.para === -1) {
        // 新的一章先读章节名
        chunks = splitSentences(d.chapters.value[index]?.title ?? "")
      } else {
        const p = paras[tts.para]
        chunks = p.type === "text" ? splitSentences(p.text) : []
      }
      if (tts.chunk >= chunks.length) {
        tts.para++
        tts.chunk = 0
        continue
      }
      // 按字数计算段内已读的比例（翻页模式下据此翻到正在读的那一页）
      const readChars = chunks.slice(0, tts.chunk).reduce((n, c) => n + c.length, 0)
      ttsFollow(index, tts.para, readChars / Math.max(1, chunks.reduce((n, c) => n + c.length, 0)))
      let text = chunks[tts.chunk]
      if (trimNext) {
        text = text.slice(trimNext).trim() || text
        trimNext = 0
      }
      tts.text = text
      speaker.prefetch(tts.para >= 0 ? upcoming(paras, tts.para, tts.chunk, chunks) : [])
      try {
        await speaker.speak(text)
      } catch (e: any) {
        if (run !== ttsRun) return
        // 不是自己停止的（例如其他页面或应用占用了语音）：暂停，点击继续可以接着读
        if (!(e instanceof CancelError)) tts.error = e.message
        tts.state = "paused"
        return
      }
      if (run !== ttsRun) return
      tts.chunk++
    }
  }

  /** 准备朗读引擎（第一次开始朗读时加载在线引擎和浏览器语音列表） */
  async function prepare(): Promise<boolean> {
    d.onStart()
    tts.active = true
    tts.error = ""
    tts.state = "loading"
    const [engines, voices] = await Promise.all([
      ttsEngines.value.length ? ttsEngines.value : ttsApi.list().catch(() => []),
      browserTts && !ttsVoices.value.length ? loadVoices() : ttsVoices.value,
    ])
    ttsEngines.value = engines
    ttsVoices.value = voices
    try {
      speaker ??= createSpeaker()
    } catch (e: any) {
      tts.error = e.message
      tts.state = "paused"
      return false
    }
    return true
  }

  async function ttsStart() {
    if (!(await prepare())) return
    tts.chapter = d.curIndex.value
    tts.para = firstVisiblePara(d.curIndex.value)
    tts.chunk = firstVisibleChunk(tts.chapter, tts.para)
    trimNext = 0
    tts.state = "playing"
    ttsLoop(++ttsRun)
  }

  /**
   * 从选中处开始朗读：para 为段落序号（-1 时从章节名开始），prefix 为段落开头到选区起点的文字。
   * 定位到选区所在的那一句，并从选区起点开始读（正在朗读时直接跳过去）
   */
  async function ttsStartAt(chapter: number, para: number, prefix: string) {
    ttsRun++
    speaker?.stop()
    if (!(await prepare())) return
    tts.chapter = chapter
    tts.para = para
    tts.chunk = 0
    trimNext = 0
    const p = para >= 0 ? ttsParas(chapter)?.[para] : null
    if (p?.type === "text") {
      // 与 splitSentences 一样合并空白、去掉开头的空白（含全角空格缩进），再按字数找到对应的句子
      const offset = prefix.replace(/\s+/g, " ").replace(/^ /, "").length
      const chunks = splitSentences(p.text)
      let acc = 0
      for (let k = 0; k < chunks.length; k++) {
        if (offset < acc + chunks[k].length || k === chunks.length - 1) {
          tts.chunk = k
          trimNext = Math.max(0, Math.min(chunks[k].length - 1, offset - acc))
          break
        }
        acc += chunks[k].length
      }
    }
    tts.state = "playing"
    ttsLoop(++ttsRun)
  }

  function ttsPause() {
    ttsRun++
    speaker?.stop()
    tts.state = "paused"
  }

  function ttsResume() {
    tts.error = ""
    try {
      speaker ??= createSpeaker()
    } catch (e: any) {
      tts.error = e.message
      return
    }
    tts.state = "playing"
    ttsLoop(++ttsRun)
  }

  function ttsToggle() {
    if (tts.state === "playing") ttsPause()
    else ttsResume()
  }

  function ttsStop() {
    ttsRun++
    speaker?.stop()
    speaker = null
    tts.active = false
    ttsActiveEl?.classList.remove("tts-active")
    ttsActiveEl = null
  }

  /** 上一段 / 下一段 */
  function ttsSkip(dir: 1 | -1) {
    const paras = ttsParas(tts.chapter) ?? []
    let i = tts.para + dir
    while (i >= 0 && i < paras.length && paras[i].type !== "text") i += dir
    tts.para = Math.max(dir < 0 ? 0 : -1, Math.min(paras.length, i))
    tts.chunk = 0
    trimNext = 0
    speaker?.stop()
    ttsResume()
  }

  // 切换引擎、语音、语速后从当前这句重新读
  let ttsCfgTimer: ReturnType<typeof setTimeout> | null = null
  watch(
    () => [d.cfg.value.ttsEngine, d.cfg.value.ttsVoice, d.cfg.value.ttsRate],
    () => {
      if (!tts.active) return
      if (ttsCfgTimer) clearTimeout(ttsCfgTimer)
      ttsCfgTimer = setTimeout(() => {
        const playing = tts.state === "playing"
        ttsRun++
        speaker?.stop()
        speaker = null
        if (playing) ttsResume()
      }, 400)
    },
  )

  // 用户切换了章节（目录、上一章 / 下一章、滚动到其他章）：从新位置开始读
  watch(d.curIndex, async (index) => {
    if (!tts.active || ttsJumping || index === tts.chapter) return
    await nextTick()
    const playing = tts.state === "playing"
    ttsRun++
    speaker?.stop()
    tts.chapter = index
    tts.para = firstVisiblePara(index)
    tts.chunk = firstVisibleChunk(index, tts.para)
    trimNext = 0
    if (playing) ttsResume()
  })

  onBeforeUnmount(ttsStop)

  // ---------- 系统媒体控制（Media Session）：锁屏、通知栏、耳机和键盘的播放键 ----------
  // 浏览器只有在页面播放音频时才显示控制：在线朗读引擎（音频）都支持；浏览器语音视浏览器而定
  const mediaSession = typeof navigator !== "undefined" && "mediaSession" in navigator ? navigator.mediaSession : null
  const mediaActions: [MediaSessionAction, () => void][] = [
    ["play", () => ttsResume()],
    ["pause", () => ttsPause()],
    ["stop", () => ttsStop()],
    ["previoustrack", () => ttsSkip(-1)],
    ["nexttrack", () => ttsSkip(1)],
  ]
  function setMediaHandlers(on: boolean) {
    for (const [action, handler] of mediaActions) {
      try {
        mediaSession?.setActionHandler(action, on ? handler : null)
      } catch {
        /* 浏览器不支持这个操作 */
      }
    }
  }
  if (mediaSession) {
    watch(
      () => [tts.active, tts.chapter] as const,
      ([active, chapter]) => {
        if (!active) {
          mediaSession.metadata = null
          mediaSession.playbackState = "none"
          setMediaHandlers(false)
          return
        }
        const cover = d.coverUrl()
        mediaSession.metadata = new MediaMetadata({
          title: d.chapters.value[chapter]?.title || d.bookName(),
          artist: d.bookName(),
          album: d.bookName(),
          artwork: cover ? [{ src: new URL(cover, location.href).href }] : [],
        })
        setMediaHandlers(true)
      },
    )
    watch(
      () => tts.state,
      (state) => {
        if (tts.active) mediaSession.playbackState = state === "paused" ? "paused" : "playing"
      },
    )
  }

  /** 朗读引擎有改动后重新加载列表；正在朗读时与切换引擎一样，从当前这句起用新的设置重新读 */
  async function reloadEngines() {
    ttsEngines.value = await ttsApi.list().catch(() => ttsEngines.value)
    if (!tts.active) return
    const playing = tts.state === "playing"
    ttsRun++
    speaker?.stop()
    speaker = null
    if (playing) ttsResume()
  }

  return { tts, ttsEngines, ttsVoices, browserTts, ttsEngineName, ttsStart, ttsStartAt, ttsToggle, ttsStop, ttsSkip, reloadEngines }
}
