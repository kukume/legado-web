import { ttsApi } from '@/api'
import { t } from '@/i18n'

/**
 * 朗读引擎：
 * - BrowserSpeaker：浏览器自带的语音合成（Web Speech API），不需要配置
 * - HttpSpeaker：在线朗读引擎（App 的 httpTTS），由服务器执行规则合成语音，这里播放返回的音频并预取后面几段
 */
export interface Speaker {
  /** 朗读一段文字，读完后 resolve；被 stop() 打断时 reject(CancelError) */
  speak(text: string): Promise<void>
  /** 提前准备后面要读的文字（在线引擎会预先请求） */
  prefetch(texts: string[]): void
  pause(): void
  resume(): void
  stop(): void
  setRate(rate: number): void
}

export class CancelError extends Error {
  constructor() {
    super('cancelled')
  }
}

/** 按句子切分，每段不超过 max 个字（浏览器语音合成遇到很长的文字会中断，在线引擎也有长度限制） */
export function splitSentences(text: string, max = 160): string[] {
  const t = text.replace(/\s+/g, ' ').trim()
  if (!t) return []
  if (t.length <= max) return [t]
  const out: string[] = []
  // 句末标点（含后面的引号、括号）处断开
  const parts = t.match(/[^。！？!?；;…]+[。！？!?；;…]*[”’"'）)」』】]*/g) || [t]
  let buf = ''
  for (const p of parts) {
    if ((buf + p).length > max && buf) {
      out.push(buf)
      buf = ''
    }
    if (p.length > max) {
      // 单句过长：按逗号或固定长度再切
      const sub = p.match(new RegExp(`.{1,${max}}(?:[，,、：:]|$)|.{1,${max}}`, 'g')) || [p]
      for (const s of sub) {
        if ((buf + s).length > max && buf) {
          out.push(buf)
          buf = ''
        }
        buf += s
      }
    } else {
      buf += p
    }
  }
  if (buf.trim()) out.push(buf)
  return out.map((s) => s.trim()).filter((s) => /[\p{L}\p{N}]/u.test(s))
}

// ---------------- 浏览器语音 ----------------

export function browserTtsSupported() {
  return typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'
}

/** 可用的语音（部分浏览器异步加载，第一次可能为空） */
export function loadVoices(): Promise<SpeechSynthesisVoice[]> {
  if (!browserTtsSupported()) return Promise.resolve([])
  const now = speechSynthesis.getVoices()
  if (now.length) return Promise.resolve(now)
  return new Promise((resolve) => {
    const done = () => resolve(speechSynthesis.getVoices())
    speechSynthesis.addEventListener('voiceschanged', done, { once: true })
    setTimeout(done, 1500)
  })
}

/** 默认语音：优先中文（普通话） */
export function pickVoice(voices: SpeechSynthesisVoice[], name?: string) {
  if (name) {
    const v = voices.find((x) => x.name === name)
    if (v) return v
  }
  const zh = voices.filter((v) => /^zh/i.test(v.lang))
  return (
    zh.find((v) => /zh[-_]CN/i.test(v.lang) && v.localService) ||
    zh.find((v) => /zh[-_]CN/i.test(v.lang)) ||
    zh[0] ||
    voices.find((v) => v.default) ||
    voices[0]
  )
}

export class BrowserSpeaker implements Speaker {
  private cancelCurrent: (() => void) | null = null
  private keepAlive: ReturnType<typeof setInterval> | null = null

  constructor(
    private voice: SpeechSynthesisVoice | undefined,
    private rate: number,
  ) {}

  speak(text: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const u = new SpeechSynthesisUtterance(text)
      if (this.voice) {
        u.voice = this.voice
        u.lang = this.voice.lang
      } else {
        u.lang = 'zh-CN'
      }
      u.rate = this.rate
      let settled = false
      const finish = (err?: Error) => {
        if (settled) return
        settled = true
        this.cancelCurrent = null
        if (this.keepAlive) clearInterval(this.keepAlive)
        this.keepAlive = null
        err ? reject(err) : resolve()
      }
      u.onend = () => finish()
      u.onerror = (e) => {
        // 被 cancel() 打断时也会触发 error（interrupted / canceled）
        if (e.error === 'interrupted' || e.error === 'canceled') finish(new CancelError())
        else finish(new Error(t('朗读出错：{0}', [e.error])))
      }
      this.cancelCurrent = () => finish(new CancelError())
      speechSynthesis.speak(u)
      // Chrome 的在线（非本地）语音读较长文字约 15 秒后会停住，定时 pause/resume 可以避免
      if (this.voice && !this.voice.localService) this.keepAlive = setInterval(() => {
        if (speechSynthesis.speaking && !speechSynthesis.paused) {
          speechSynthesis.pause()
          speechSynthesis.resume()
        }
      }, 10_000)
    })
  }

  prefetch() {}

  pause() {
    speechSynthesis.pause()
  }

  resume() {
    speechSynthesis.resume()
  }

  stop() {
    const c = this.cancelCurrent
    speechSynthesis.cancel()
    c?.()
  }

  setRate(rate: number) {
    this.rate = rate
  }
}

// ---------------- 在线朗读引擎 ----------------

export class HttpSpeaker implements Speaker {
  private audio = new Audio()
  private cache = new Map<string, Promise<string>>()
  private controller = new AbortController()
  private cancelCurrent: (() => void) | null = null

  constructor(
    private engineId: number,
    private rate: number,
  ) {
    this.audio.preload = 'auto'
  }

  /** 与 App 相同：speakSpeed 为 10 时是正常语速 */
  private get speed() {
    return Math.max(1, Math.min(50, Math.round(this.rate * 10)))
  }

  private key(text: string) {
    return `${this.speed}|${text}`
  }

  private fetchUrl(text: string): Promise<string> {
    const k = this.key(text)
    let p = this.cache.get(k)
    if (!p) {
      p = ttsApi.speak(this.engineId, text, this.speed, this.controller.signal).then((b) => URL.createObjectURL(b))
      p.catch(() => this.cache.delete(k))
      this.cache.set(k, p)
      // 只保留最近的若干段
      if (this.cache.size > 12) {
        const [oldKey, old] = this.cache.entries().next().value as [string, Promise<string>]
        this.cache.delete(oldKey)
        old.then((u) => URL.revokeObjectURL(u)).catch(() => {})
      }
    }
    return p
  }

  prefetch(texts: string[]) {
    for (const t of texts.slice(0, 2)) this.fetchUrl(t).catch(() => {})
  }

  async speak(text: string): Promise<void> {
    let cancelled = false
    const cancelWait = new Promise<never>((_, reject) => {
      this.cancelCurrent = () => {
        cancelled = true
        reject(new CancelError())
      }
    })
    const url = await Promise.race([this.fetchUrl(text), cancelWait])
    if (cancelled) throw new CancelError()
    await new Promise<void>((resolve, reject) => {
      const a = this.audio
      const cleanup = () => {
        a.onended = null
        a.onerror = null
        this.cancelCurrent = null
      }
      a.onended = () => {
        cleanup()
        resolve()
      }
      a.onerror = () => {
        cleanup()
        reject(new Error(t('朗读音频播放失败')))
      }
      this.cancelCurrent = () => {
        cleanup()
        a.pause()
        reject(new CancelError())
      }
      a.src = url
      a.play().catch((e) => {
        cleanup()
        reject(e?.name === 'NotAllowedError' ? new Error(t('浏览器阻止了自动播放，请再点一次朗读')) : e)
      })
    })
  }

  pause() {
    this.audio.pause()
  }

  resume() {
    this.audio.play().catch(() => {})
  }

  stop() {
    this.cancelCurrent?.()
    this.audio.pause()
    this.controller.abort()
    this.controller = new AbortController()
    for (const p of this.cache.values()) p.then((u) => URL.revokeObjectURL(u)).catch(() => {})
    this.cache.clear()
  }

  setRate(rate: number) {
    if (rate === this.rate) return
    this.rate = rate
  }
}
