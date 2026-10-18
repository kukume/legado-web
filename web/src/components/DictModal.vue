<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NButton, NEmpty, NInput, NModal, NSpin, NTab, NTabs, useMessage, useThemeVars } from 'naive-ui'
import { marked } from 'marked'
import { dictApi } from '@/api'
import type { DictRule } from '@/api/types'
import { t } from '@/i18n'

/**
 * 查字典（App 的正文选择菜单“字典”）：每个启用的字典规则一个标签，切换到标签时查询。
 * 规则返回的 HTML 放在不允许运行脚本的 iframe 里显示；以 <md> 开头的结果按 Markdown 显示（与 App 相同）。
 *
 * iframe 的 sandbox 有 allow-same-origin、没有 allow-scripts：结果里的脚本不会执行，
 * 外层页面可以处理里面的点击（播放读音）。两者同时开启会让内容逃出沙箱，不要再加 allow-scripts。
 */
const props = defineProps<{ word: string; /** 只用这一条规则（规则页的“测试”） */ ruleName?: string }>()
const show = defineModel<boolean>('show', { default: false })

const themeVars = useThemeVars()
const message = useMessage()
const rules = ref<DictRule[]>([])
const loadingRules = ref(false)
const loadError = ref('')
const active = ref('')
const query = ref('')
/** 规则名 -> 查询结果（换词后清空） */
const results = ref<Record<string, { loading: boolean; html?: string; baseUrl?: string | null; error?: string }>>({})

watch(show, async (v) => {
  if (!v) return stopAudio()
  query.value = props.word.trim()
  results.value = {}
  loadError.value = ''
  loadingRules.value = true
  try {
    const all = (await dictApi.list()).rules
    rules.value = props.ruleName ? all.filter((r) => r.name === props.ruleName) : all.filter((r) => r.enabled)
  } catch (e: any) {
    loadError.value = e.message
    rules.value = []
  } finally {
    loadingRules.value = false
  }
  active.value = rules.value[0]?.name ?? ''
  if (active.value) search(active.value)
})

async function search(name: string) {
  const word = query.value.trim()
  if (!word || results.value[name]?.loading || results.value[name]?.html !== undefined) return
  results.value[name] = { loading: true }
  try {
    const { html, baseUrl } = await dictApi.search(name, word)
    if (query.value.trim() === word) results.value[name] = { loading: false, html, baseUrl }
  } catch (e: any) {
    if (query.value.trim() === word) results.value[name] = { loading: false, error: e.message }
  }
}

function onTab(name: string) {
  stopAudio()
  active.value = name
  search(name)
}

function research() {
  stopAudio()
  results.value = {}
  if (active.value) search(active.value)
}

const current = computed(() => results.value[active.value])

// ---------- 读音 ----------
let audio: HTMLAudioElement | null = null
let playingEl: Element | null = null

function stopAudio() {
  audio?.pause()
  audio = null
  playingEl?.classList.remove('playing')
  playingEl = null
}

function playAudio(el: Element, url: string) {
  const same = playingEl === el
  stopAudio()
  if (same) return // 再点一次停止
  const a = new Audio(url)
  audio = a
  playingEl = el
  el.classList.add('playing')
  const fail = () => {
    if (audio !== a) return
    stopAudio()
    message.warning(t('读音播放失败'))
  }
  a.addEventListener('ended', () => audio === a && stopAudio())
  a.addEventListener('error', fail)
  a.play().catch((e) => e?.name !== 'AbortError' && fail())
}

/** iframe 加载后接管里面的点击：读音按钮在外层播放 */
function onFrameLoad(e: Event) {
  const doc = (e.target as HTMLIFrameElement).contentDocument
  if (!doc) return
  doc.addEventListener('click', (ev) => {
    const el = (ev.target as Element | null)?.closest?.('[data-dict-audio]')
    if (!el) return
    ev.preventDefault()
    playAudio(el, el.getAttribute('data-dict-audio')!)
  })
}

onBeforeUnmount(stopAudio)

function toHtml(raw: string): string {
  const s = raw.trimStart()
  if (s.startsWith('<md>')) {
    const end = s.lastIndexOf('<')
    return end < 4 ? s : (marked.parse(s.slice(4, end), { async: false }) as string)
  }
  return cleanHtml(raw, current.value?.baseUrl ?? null)
}

const AUDIO_EXT = /\.(mp3|m4a|aac|ogg|oga|wav|opus)(\?|#|$)/i
const AUDIO_IN_TEXT = /['"]([^'"]+\.(?:mp3|m4a|aac|ogg|oga|wav|opus)(?:\?[^'"]*)?)['"]/i

/** 带读音的元素上的音频地址：audio / data-audio 等属性、onclick 里的音频地址、指向音频文件的链接、<audio> */
function audioUrlOf(el: Element): string | null {
  for (const name of ['audio', 'data-audio', 'data-mp3', 'data-sound', 'data-voice', 'data-src', 'data-url']) {
    const v = el.getAttribute(name)?.trim()
    if (v && (name.includes('audio') || name === 'data-mp3' || AUDIO_EXT.test(v))) return v
  }
  const m = el.getAttribute('onclick')?.match(AUDIO_IN_TEXT)
  if (m) return m[1]
  if (el.tagName === 'A' && AUDIO_EXT.test(el.getAttribute('href') ?? '')) return el.getAttribute('href')
  if (el.tagName === 'AUDIO') return el.getAttribute('src') || el.querySelector('source[src]')?.getAttribute('src') || null
  return null
}

/**
 * 读音：网页原本靠自己的脚本播放、用自己的 CSS 显示喇叭图标（如海词的透明小图片），这里都没有。
 * 没有文字的换成看得见的按钮，有文字的（如“英 [həˈləʊ]”）保留文字；点击时由外层页面播放
 */
function markAudio(doc: Document, baseUrl: string | null) {
  const candidates = doc.body.querySelectorAll('[audio], [data-audio], [data-mp3], [data-sound], [data-voice], [data-src], [data-url], [onclick], a[href], audio')
  for (const el of [...candidates]) {
    const raw = audioUrlOf(el)
    if (!raw) continue
    let url: string
    try {
      url = new URL(raw, baseUrl ?? undefined).href
    } catch {
      continue
    }
    if (!/^https?:/i.test(url)) continue
    // 网站是 https 时，http 的音频会被浏览器拦截（混合内容），先换成 https
    if (location.protocol === 'https:' && url.startsWith('http:')) url = 'https:' + url.slice(5)
    if (el.tagName !== 'IMG' && el.tagName !== 'AUDIO' && el.textContent?.trim()) {
      el.removeAttribute('onclick')
      el.setAttribute('data-dict-audio', url)
      el.classList.add('dict-audio-text')
    } else {
      const btn = doc.createElement('button')
      btn.type = 'button'
      btn.className = 'dict-audio'
      btn.setAttribute('data-dict-audio', url)
      btn.title = el.getAttribute('title') || t('播放读音')
      btn.textContent = '🔊'
      el.replaceWith(btn)
    }
  }
}

/**
 * 整理字典网页的内容（结果里不运行脚本）：
 * - 懒加载的图片（src 是占位图，真实地址在 data-src 等属性里）换成真实地址
 * - 去掉 Flash 等插件（如海词单字页的笔顺动画，浏览器早已不支持）和留下的“加载中”占位图
 */
function cleanHtml(html: string, baseUrl: string | null): string {
  if (!/<(img|object|embed|audio)\b|\.(mp3|m4a|aac|ogg|oga|wav|opus)\b|\saudio=/i.test(html)) return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('object, embed, applet').forEach((el) => el.remove())
  markAudio(doc, baseUrl)
  for (const img of doc.querySelectorAll('img')) {
    const real = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src')
    if (real) img.setAttribute('src', real)
    else if (/(^|\/)(loading|placeholder|spinner)[^/]*\.(gif|png|svg)(\?|$)/i.test(img.getAttribute('src') ?? '')) img.remove()
  }
  return doc.body.innerHTML
}

/** HTML 属性值转义 */
const attr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')

/** iframe 内容：不运行脚本，链接在新标签页打开，颜色跟随当前主题 */
const srcdoc = computed(() => {
  const html = current.value?.html
  if (html === undefined) return ''
  const v = themeVars.value
  // base：结果里的相对地址（图片、链接）按字典网页的地址解析，否则会指向本站
  const base = current.value?.baseUrl ? ` href="${attr(current.value.baseUrl)}"` : ''
  return `<!doctype html><html><head><meta charset="utf-8"><base target="_blank"${base}>
<style>
html,body{margin:0;background:transparent}
body{font:14px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;color:${v.textColor2};word-break:break-word;padding:2px 4px}
a{color:${v.primaryColor}}
img,video{max-width:100%;height:auto}
[style*="width"]{max-width:100%!important;box-sizing:border-box}
h1,h2,h3{color:${v.textColor1};line-height:1.4}
h1{font-size:20px}h2{font-size:17px}h3{font-size:15px}
table{border-collapse:collapse;max-width:100%}td,th{border:1px solid ${v.dividerColor};padding:2px 6px}
pre{white-space:pre-wrap}
.dict-audio{border:0;background:none;cursor:pointer;padding:0 3px;font-size:15px;line-height:1;vertical-align:middle;opacity:.75}
.dict-audio:hover,.dict-audio.playing{opacity:1}
.dict-audio.playing,.dict-audio-text.playing::after{animation:dict-pulse 1s ease-in-out infinite}
.dict-audio-text{cursor:pointer}
.dict-audio-text::after{content:" 🔊";font-size:13px;opacity:.75}
@keyframes dict-pulse{50%{opacity:.35}}
</style></head><body>${toHtml(html)}</body></html>`
})
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="ruleName ? t('测试字典规则：{0}', [ruleName]) : t('字典')" class="dict-modal" :auto-focus="false">
    <div class="dict-query">
      <n-input v-model:value="query" size="small" :placeholder="t('要查询的文字')" clearable @keyup.enter="research" />
      <n-button size="small" :disabled="!query.trim()" @click="research">{{ t('查询') }}</n-button>
    </div>
    <div v-if="loadingRules" class="dict-center"><n-spin size="small" /></div>
    <div v-else-if="loadError" class="muted dict-center">{{ loadError }}</div>
    <n-empty v-else-if="!rules.length" :description="t('没有启用的字典规则，可以在“规则 → 字典规则”中启用或导入')" class="dict-center" />
    <template v-else>
      <n-tabs v-if="rules.length > 1" :value="active" type="line" size="small" @update:value="onTab">
        <n-tab v-for="r in rules" :key="r.name" :name="r.name">{{ r.name }}</n-tab>
      </n-tabs>
      <div class="dict-body">
        <div v-if="!current || current.loading" class="dict-center"><n-spin size="small" /></div>
        <div v-else-if="current.error" class="muted dict-error">{{ t('查询失败：{0}', [current.error]) }}</div>
        <iframe
          v-else
          class="dict-frame"
          sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
          :srcdoc="srcdoc"
          :title="t('字典结果')"
          @load="onFrameLoad"
        />
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.dict-query {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.dict-body {
  margin-top: 8px;
  height: min(60vh, 520px);
}

.dict-frame {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}

.dict-center {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 120px;
}

.dict-error {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
}
</style>

<style>
.dict-modal {
  width: 560px;
  max-width: 94vw;
}
</style>
