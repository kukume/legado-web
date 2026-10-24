<script setup lang="ts">
import { goBack } from '@/utils/navigation'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NDrawer, NDrawerContent, NDropdown, NIcon, NInput, NSlider, NSpin, NAlert, useMessage } from 'naive-ui'
import {
  ArrowBackOutline, ListOutline, PlayCircle, PauseCircle, PlaySkipBack, PlaySkipForward, TimerOutline, SpeedometerOutline,
} from '@vicons/ionicons5'
import BookCover from '@/components/BookCover.vue'
import { audioApi, shelfApi } from '@/api'
import { getToken, proxyImage } from '@/api/http'
import type { Book, Chapter } from '@/api/types'
import { t } from '@/i18n'

defineOptions({ name: 'AudioView' })

const route = useRoute()
const router = useRouter()
const message = useMessage()
const bookId = computed(() => Number(route.params.id))

const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const cur = ref(0)
const loading = ref(true)
const buffering = ref(false)
const error = ref('')
const playing = ref(false)
const position = ref(0)
const duration = ref(0)
/** 拖动进度条时暂停界面更新 */
const seeking = ref(false)
const audio = ref<HTMLAudioElement | null>(null)
let hls: any = null
/** 本集是否已经用刷新后的地址重试过 */
let retried = false

const RATE_KEY = 'legado_audio_rate'
const rate = ref(Number(localStorage.getItem(RATE_KEY)) || 1)
const rates = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3]
const rateOptions = computed(() => rates.map((r) => ({ label: `${r}x${r === rate.value ? '  ✓' : ''}`, key: r })))

const chapter = computed(() => chapters.value[cur.value])
const coverUrl = computed(() => book.value?.customCoverUrl || book.value?.coverUrl || '')

function fmt(sec: number) {
  if (!isFinite(sec) || sec < 0) sec = 0
  const s = Math.floor(sec % 60)
  const m = Math.floor(sec / 60) % 60
  const h = Math.floor(sec / 3600)
  const mm = String(m).padStart(h ? 2 : 1, '0')
  return `${h ? h + ':' : ''}${mm}:${String(s).padStart(2, '0')}`
}

// ---------------- 加载 ----------------

onMounted(async () => {
  try {
    const [b, c] = await Promise.all([shelfApi.get(bookId.value), shelfApi.chapters(bookId.value)])
    book.value = b
    chapters.value = c
    document.title = t('{0} - 听书', [b.name])
    if (!c.length) {
      error.value = t('目录为空，请在书籍详情中更新目录')
      return
    }
    const q = route.query.index !== undefined ? Number(route.query.index) : null
    const index = Math.min(c.length - 1, Math.max(0, q ?? b.durChapterIndex))
    // 同一集接着上次的位置（毫秒）播放
    const startMs = q === null || q === b.durChapterIndex ? b.durChapterPos : 0
    await load(index, startMs, false)
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
  window.addEventListener('keydown', onKey)
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  saveProgress(true)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('beforeunload', onBeforeUnload)
  if (sleepTimer) clearInterval(sleepTimer)
  destroyHls()
  audio.value?.pause()
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null
})

function destroyHls() {
  if (hls) {
    hls.destroy()
    hls = null
  }
}

/** 加载第 index 集，从 startMs 开始；autoplay 为 true 时加载后自动播放 */
async function load(index: number, startMs = 0, autoplay = true, refresh = false) {
  const el = audio.value
  if (!el || !chapters.value[index]) return
  if (index !== cur.value) saveProgress()
  cur.value = index
  error.value = ''
  retried = refresh
  buffering.value = true
  position.value = startMs / 1000
  duration.value = 0
  destroyHls()
  el.pause()
  pendingSeek = startMs / 1000
  pendingPlay = autoplay
  updateMediaSession()
  try {
    const info = await audioApi.info(bookId.value, index, refresh)
    if (cur.value !== index) return
    const src = audioApi.src(bookId.value, index, refresh)
    if (info.hls && !el.canPlayType('application/vnd.apple.mpegurl')) {
      const { default: Hls } = await import('hls.js')
      if (!Hls.isSupported()) throw new Error(t('当前浏览器不支持播放 m3u8'))
      hls = new Hls({
        xhrSetup: (xhr: XMLHttpRequest) => xhr.setRequestHeader('Authorization', `Bearer ${getToken()}`),
      })
      hls.on(Hls.Events.ERROR, (_: any, data: any) => {
        if (data.fatal) onAudioError()
      })
      hls.loadSource(src)
      hls.attachMedia(el)
    } else {
      el.src = src
      el.load()
    }
  } catch (e: any) {
    buffering.value = false
    error.value = e.message
  }
}

let pendingSeek = 0
let pendingPlay = false

function onLoadedMetadata() {
  const el = audio.value!
  el.playbackRate = rate.value
  duration.value = el.duration || 0
  if (pendingSeek > 0 && (!el.duration || pendingSeek < el.duration - 1)) el.currentTime = pendingSeek
  pendingSeek = 0
  if (pendingPlay) play()
  buffering.value = false
}

function onAudioError() {
  // 音频地址可能过期：重新从书源获取一次
  if (!retried) {
    load(cur.value, Math.round((audio.value?.currentTime || position.value) * 1000), playing.value || pendingPlay, true)
    return
  }
  buffering.value = false
  playing.value = false
  error.value = t('音频加载失败，可能是书源失效或地址已过期')
}

async function play() {
  try {
    await audio.value?.play()
  } catch (e: any) {
    // 浏览器禁止未经点击的自动播放：等用户点击播放
    if (e?.name !== 'NotAllowedError' && e?.name !== 'AbortError') error.value = e.message
  }
}

function toggle() {
  const el = audio.value
  if (!el) return
  if (error.value) return load(cur.value, Math.round(position.value * 1000), true, true)
  if (el.paused) play()
  else el.pause()
}

function seekBy(sec: number) {
  const el = audio.value
  if (!el) return
  el.currentTime = Math.max(0, Math.min((el.duration || Infinity) - 0.5, el.currentTime + sec))
}

function onSeek(v: number) {
  position.value = v
  if (audio.value) audio.value.currentTime = v
}

function nextIndex(from: number, dir: 1 | -1) {
  let i = from + dir
  while (chapters.value[i]?.isVolume) i += dir
  return i >= 0 && i < chapters.value.length ? i : -1
}

function prev() {
  const i = nextIndex(cur.value, -1)
  if (i < 0) return message.info(t('已经是第一集了'))
  load(i, 0, true)
}

function next(auto = false) {
  const i = nextIndex(cur.value, 1)
  if (i < 0) {
    if (!auto) message.info(t('已经是最后一集了'))
    return
  }
  load(i, 0, true)
}

function onEnded() {
  playing.value = false
  if (sleepMode.value === 'chapter') {
    stopSleep()
    saveProgress()
    return
  }
  next(true)
}

function onTimeUpdate() {
  const el = audio.value!
  if (!seeking.value) position.value = el.currentTime
  if (el.duration && el.duration !== duration.value) duration.value = el.duration
  if (Date.now() - lastSave > 10_000 && playing.value) saveProgress()
}

watch(rate, (r) => {
  if (audio.value) audio.value.playbackRate = r
  localStorage.setItem(RATE_KEY, String(r))
})

// ---------------- 进度 ----------------

let lastSave = 0
function saveProgress(keepalive = false) {
  if (!book.value || !chapter.value) return
  lastSave = Date.now()
  const index = cur.value
  const pos = Math.round((audio.value?.currentTime ?? position.value) * 1000)
  const title = chapter.value.title
  if (keepalive) {
    fetch(`/api/shelf/${bookId.value}/progress`, {
      method: 'PUT',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ index, pos, title, time: Date.now() }),
    }).catch(() => {})
  } else {
    shelfApi.progress(bookId.value, index, pos, title, Date.now()).catch(() => {})
  }
}

function onBeforeUnload() {
  saveProgress(true)
}

// ---------------- 定时关闭 ----------------

const sleepMode = ref<'off' | 'timer' | 'chapter'>('off')
const sleepLeft = ref(0)
let sleepTimer: ReturnType<typeof setInterval> | null = null
const sleepOptions = [
  { label: t('不开启'), key: 'off' },
  { label: t('15 分钟'), key: 15 },
  { label: t('30 分钟'), key: 30 },
  { label: t('45 分钟'), key: 45 },
  { label: t('60 分钟'), key: 60 },
  { label: t('90 分钟'), key: 90 },
  { label: t('播完本集'), key: 'chapter' },
]
const sleepText = computed(() =>
  sleepMode.value === 'timer' ? fmt(sleepLeft.value) : sleepMode.value === 'chapter' ? t('本集结束') : t('定时'),
)

function stopSleep() {
  if (sleepTimer) clearInterval(sleepTimer)
  sleepTimer = null
  sleepMode.value = 'off'
}

function onSleep(key: string | number) {
  stopSleep()
  if (key === 'off') return
  if (key === 'chapter') {
    sleepMode.value = 'chapter'
    return
  }
  sleepMode.value = 'timer'
  sleepLeft.value = Number(key) * 60
  sleepTimer = setInterval(() => {
    if (!playing.value) return
    if (--sleepLeft.value <= 0) {
      audio.value?.pause()
      stopSleep()
      saveProgress()
    }
  }, 1000)
}

// ---------------- 目录 ----------------

const showToc = ref(false)
const tocFilter = ref('')
const tocRef = ref<HTMLElement | null>(null)
const shownChapters = computed(() => {
  const f = tocFilter.value.trim()
  return f ? chapters.value.filter((c) => c.title.includes(f)) : chapters.value
})
function scrollTocToCurrent() {
  const i = shownChapters.value.findIndex((c) => c.index === cur.value)
  if (i < 0 || !tocRef.value) return
  tocRef.value.scrollTo({ top: Math.max(0, i * 42 - tocRef.value.clientHeight / 2 + 21) })
}
function pick(c: Chapter) {
  if (c.isVolume) return
  showToc.value = false
  load(c.index, 0, true)
}
watch(tocFilter, (f) => !f.trim() && nextTick(scrollTocToCurrent))

// ---------------- 系统媒体控制（锁屏、耳机按键） ----------------

function updateMediaSession() {
  if (!('mediaSession' in navigator) || !book.value) return
  const cover = coverUrl.value ? proxyImage(coverUrl.value, { origin: book.value.origin, cover: true }) : ''
  navigator.mediaSession.metadata = new MediaMetadata({
    title: chapter.value?.title || book.value.name,
    artist: book.value.author,
    album: book.value.name,
    artwork: cover ? [{ src: new URL(cover, location.href).href }] : [],
  })
  const ms = navigator.mediaSession
  const set = (a: MediaSessionAction, h: MediaSessionActionHandler | null) => {
    try {
      ms.setActionHandler(a, h)
    } catch {
      /* 浏览器不支持该操作 */
    }
  }
  set('play', () => play())
  set('pause', () => audio.value?.pause())
  set('previoustrack', () => prev())
  set('nexttrack', () => next())
  set('seekbackward', () => seekBy(-15))
  set('seekforward', () => seekBy(15))
  set('seekto', (d) => d.seekTime !== undefined && onSeek(d.seekTime))
}

// ---------------- 快捷键 ----------------

function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  if (e.key === ' ') {
    e.preventDefault()
    toggle()
  } else if (e.key === 'ArrowLeft') seekBy(-15)
  else if (e.key === 'ArrowRight') seekBy(15)
  else if (e.key === '[') prev()
  else if (e.key === ']') next()
}

function back() {
  goBack(router, { name: 'book', params: { id: bookId.value } })
}
</script>

<template>
  <div class="listen">
    <header class="bar">
      <n-button quaternary circle @click="back">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <div class="bar-title ellipsis">{{ book?.name }}</div>
      <n-button quaternary circle :title="t('目录')" :disabled="!chapters.length" @click="showToc = true">
        <template #icon><n-icon :component="ListOutline" /></template>
      </n-button>
    </header>

    <n-spin :show="loading" class="body-spin">
      <main v-if="book" class="body">
        <book-cover :url="coverUrl" :name="book.name" :author="book.author" :origin="book.origin" :width="210" class="cover" />
        <div class="titles">
          <h1 class="ellipsis-2">{{ chapter?.title || book.name }}</h1>
          <div class="muted ellipsis">{{ book.name }}<template v-if="book.author"> · {{ book.author }}</template></div>
          <div class="muted small ellipsis">{{ t('第 {0} / {1} 集 · {2}', [cur + 1, chapters.length, book.originName]) }}</div>
        </div>

        <n-alert v-if="error" type="error" :bordered="false" class="err">
          {{ error }}
          <n-button size="tiny" style="margin-left: 8px" @click="load(cur, Math.round(position * 1000), true, true)">{{ t('重试') }}</n-button>
        </n-alert>

        <div class="progress">
          <n-slider
            :value="position"
            :min="0"
            :max="Math.max(duration, 1)"
            :step="1"
            :tooltip="false"
            :disabled="!duration"
            @update:value="(v: number) => { seeking = true; position = v }"
            @dragend="() => { seeking = false; onSeek(position) }"
            @mouseup="() => { if (seeking) { seeking = false; onSeek(position) } }"
            @touchend="() => { if (seeking) { seeking = false; onSeek(position) } }"
          />
          <div class="times muted small">
            <span>{{ fmt(position) }}</span>
            <span v-if="buffering" class="buffering">{{ t('加载中…') }}</span>
            <span>{{ duration ? fmt(duration) : '--:--' }}</span>
          </div>
        </div>

        <div class="controls">
          <button class="ctl" :title="t('上一集 [')" @click="prev"><n-icon :component="PlaySkipBack" size="26" /></button>
          <button class="ctl text" :title="t('后退 15 秒 ←')" @click="seekBy(-15)">-15s</button>
          <button class="ctl main" :title="playing ? t('暂停（空格）') : t('播放（空格）')" @click="toggle">
            <n-icon :component="playing ? PauseCircle : PlayCircle" size="72" />
          </button>
          <button class="ctl text" :title="t('前进 15 秒 →')" @click="seekBy(15)">+15s</button>
          <button class="ctl" :title="t('下一集 ]')" @click="next()"><n-icon :component="PlaySkipForward" size="26" /></button>
        </div>

        <div class="extras">
          <n-dropdown :options="rateOptions" trigger="click" @select="(k: number) => (rate = k)">
            <n-button quaternary size="small">
              <template #icon><n-icon :component="SpeedometerOutline" /></template>
              {{ rate }}x
            </n-button>
          </n-dropdown>
          <n-dropdown :options="sleepOptions" trigger="click" @select="onSleep">
            <n-button quaternary size="small" :type="sleepMode !== 'off' ? 'primary' : 'default'">
              <template #icon><n-icon :component="TimerOutline" /></template>
              {{ sleepText }}
            </n-button>
          </n-dropdown>
          <n-button quaternary size="small" @click="showToc = true">
            <template #icon><n-icon :component="ListOutline" /></template>
            {{ t('目录') }}
          </n-button>
        </div>
      </main>
    </n-spin>

    <audio
      ref="audio"
      preload="auto"
      @loadedmetadata="onLoadedMetadata"
      @durationchange="duration = audio?.duration || 0"
      @timeupdate="onTimeUpdate"
      @play="playing = true"
      @playing="buffering = false"
      @pause="playing = false; saveProgress()"
      @waiting="buffering = true"
      @canplay="buffering = false"
      @ended="onEnded"
      @error="onAudioError"
    />

    <n-drawer v-model:show="showToc" :width="360" placement="right" style="max-width: 88vw" :auto-focus="false" @after-enter="scrollTocToCurrent">
      <n-drawer-content :title="t('目录（{0} 集）', [chapters.length])" :native-scrollbar="false" body-content-style="padding: 0">
        <div class="toc-filter"><n-input v-model:value="tocFilter" size="small" :placeholder="t('搜索')" clearable /></div>
        <div ref="tocRef" class="toc">
          <div
            v-for="c in shownChapters"
            :key="c.index"
            class="toc-item ellipsis"
            :class="{ active: c.index === cur, volume: c.isVolume }"
            @click="pick(c)"
          >{{ c.title }}</div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.listen {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--app-bg);
  color: var(--app-text);
}

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
}

.bar-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  text-align: center;
}

.body-spin {
  flex: 1;
}

.body {
  max-width: 520px;
  margin: 0 auto;
  padding: 12px 20px 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
}

.cover {
  margin-top: 12px;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25);
}

.titles {
  width: 100%;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.titles h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.35;
}

.err {
  width: 100%;
}

.progress {
  width: 100%;
}

.times {
  display: flex;
  justify-content: space-between;
  margin-top: 2px;
}

.buffering {
  color: var(--app-text-2);
}

.controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.ctl {
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  padding: 6px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  -webkit-tap-highlight-color: transparent;
}

.ctl:hover {
  background: rgba(127, 127, 127, 0.12);
}

.ctl.text {
  font-size: 13px;
  font-weight: 600;
  min-width: 44px;
  min-height: 44px;
}

.ctl.main {
  color: var(--app-accent);
  padding: 0;
}

.ctl.main:hover {
  background: none;
  transform: scale(1.04);
}

.extras {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.small {
  font-size: 12.5px;
}

.toc-filter {
  padding: 10px 16px;
}

.toc {
  max-height: calc(100vh - 120px);
  overflow: auto;
}

.toc-item {
  height: 42px;
  line-height: 42px;
  padding: 0 16px;
  cursor: pointer;
  font-size: 14px;
}

.toc-item:hover {
  background: rgba(127, 127, 127, 0.1);
}

.toc-item.active {
  color: var(--app-accent);
  font-weight: 600;
}

.toc-item.volume {
  cursor: default;
  font-weight: 600;
  opacity: 0.7;
}
</style>
