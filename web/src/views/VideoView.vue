<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NDrawer, NDrawerContent, NDropdown, NIcon, NSpin, useMessage } from 'naive-ui'
import {
  ArrowBackOutline, Play, AlbumsOutline, SpeedometerOutline, VolumeMuteOutline, ExpandOutline, ContractOutline, PlayForward,
} from '@vicons/ionicons5'
import { audioApi, shelfApi } from '@/api'
import { getToken, proxyImage } from '@/api/http'
import type { Book, Chapter } from '@/api/types'
import { attachChapterMedia } from '@/utils/mediaSource'
import { t } from '@/i18n'

defineOptions({ name: 'VideoView' })

const route = useRoute()
const router = useRouter()
const message = useMessage()
const bookId = computed(() => Number(route.params.id))

const book = ref<Book | null>(null)
/** 剧集（去掉分卷标题） */
const eps = ref<Chapter[]>([])
/** 当前是第几集（eps 中的下标） */
const cur = ref(0)
const loading = ref(true)
const buffering = ref(false)
const error = ref('')
const playing = ref(false)
const muted = ref(false)
const position = ref(0)
const duration = ref(0)
const video = ref<HTMLVideoElement | null>(null)
let release: (() => void) | null = null
let retried = false
let pendingSeek = 0

const RATE_KEY = 'legado_video_rate'
const rate = ref(Number(localStorage.getItem(RATE_KEY)) || 1)
const rateOptions = computed(() =>
  [0.75, 1, 1.25, 1.5, 2, 3].map((r) => ({ label: `${r}x${r === rate.value ? '  ✓' : ''}`, key: r })),
)

const episode = computed(() => eps.value[cur.value])
const cover = computed(() => {
  const b = book.value
  const u = b?.customCoverUrl || b?.coverUrl
  return b && u ? proxyImage(u, { origin: b.origin, cover: true }) : ''
})

function fmt(sec: number) {
  if (!isFinite(sec) || sec < 0) sec = 0
  const s = Math.floor(sec % 60)
  const m = Math.floor(sec / 60)
  return `${m}:${String(s).padStart(2, '0')}`
}

// ---------------- 加载 ----------------

onMounted(async () => {
  try {
    const [b, c] = await Promise.all([shelfApi.get(bookId.value), shelfApi.chapters(bookId.value)])
    book.value = b
    eps.value = c.filter((x) => !x.isVolume)
    document.title = t('{0} - 短剧', [b.name])
    if (!eps.value.length) {
      error.value = t('没有剧集，请在详情页更新目录')
      return
    }
    const q = route.query.index !== undefined ? Number(route.query.index) : null
    const target = q ?? b.durChapterIndex
    let i = eps.value.findIndex((e) => e.index >= target)
    if (i < 0) i = eps.value.length - 1
    const startMs = eps.value[i].index === b.durChapterIndex && (q === null || q === b.durChapterIndex) ? b.durChapterPos : 0
    loading.value = false
    await nextTick()
    await load(i, startMs)
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
  release?.()
  video.value?.pause()
})

async function load(i: number, startMs = 0, refresh = false) {
  const el = video.value
  if (!el || !eps.value[i]) return
  if (i !== cur.value) saveProgress()
  cur.value = i
  error.value = ''
  retried = refresh
  buffering.value = true
  position.value = startMs / 1000
  duration.value = 0
  pendingSeek = startMs / 1000
  release?.()
  release = null
  el.pause()
  try {
    const r = await attachChapterMedia(el, bookId.value, eps.value[i].index, refresh, onVideoError)
    if (cur.value !== i) return r()
    release = r
  } catch (e: any) {
    buffering.value = false
    error.value = e.message
  }
}

function onLoadedMetadata() {
  const el = video.value!
  el.playbackRate = boosting.value ? boostRate() : rate.value
  updateOrientation()
  duration.value = el.duration || 0
  if (pendingSeek > 0 && (!el.duration || pendingSeek < el.duration - 1)) el.currentTime = pendingSeek
  pendingSeek = 0
  buffering.value = false
  play()
  prefetchNext()
}

/**
 * 提前获取下一集的播放地址：有的书源（如番茄短剧）要等后台解密转码才返回地址，
 * 提前请求让后台先处理，滑到下一集时不用等待（服务器会合并同一集的重复请求）
 */
const prefetched = new Set<number>()
function prefetchNext() {
  const next = eps.value[cur.value + 1]
  if (!next || prefetched.has(next.index)) return
  prefetched.add(next.index)
  audioApi.info(bookId.value, next.index).catch(() => prefetched.delete(next.index))
}

function onVideoError() {
  // 视频地址可能过期：重新从书源获取一次
  if (!retried) {
    load(cur.value, Math.round((video.value?.currentTime || position.value) * 1000), true)
    return
  }
  buffering.value = false
  playing.value = false
  error.value = t('视频加载失败，可能是书源失效或地址已过期')
}

async function play() {
  const el = video.value
  if (!el) return
  try {
    await el.play()
  } catch (e: any) {
    if (e?.name === 'NotAllowedError') {
      // 浏览器不允许有声自动播放：先静音播放，点一下取消静音
      el.muted = true
      muted.value = true
      el.play().catch(() => {})
    }
  }
}

function toggle() {
  const el = video.value
  if (!el) return
  if (muted.value) {
    el.muted = false
    muted.value = false
    if (!el.paused) return
  }
  if (error.value) return load(cur.value, Math.round(position.value * 1000), true)
  if (el.paused) play()
  else el.pause()
}

function onTimeUpdate() {
  const el = video.value!
  if (!draggingBar.value) position.value = el.currentTime
  if (el.duration && el.duration !== duration.value) duration.value = el.duration
  if (Date.now() - lastSave > 10_000 && playing.value) saveProgress()
}

function onEnded() {
  playing.value = false
  if (cur.value < eps.value.length - 1) go(1)
  else message.info(t('已经是最后一集了'))
}

watch(rate, (r) => {
  if (video.value && !boosting.value) video.value.playbackRate = r
  localStorage.setItem(RATE_KEY, String(r))
})

// ---------------- 上下滑动切换剧集 ----------------

const stage = ref<HTMLElement | null>(null)
const dragY = ref(0)
const animating = ref(false)
/** 滑动动画：-1 上一集，1 下一集 */
const sliding = ref(0)

const trackStyle = computed(() => {
  // 播放区域的高度（播放页铺满窗口）。直接按窗口尺寸计算，不读页面元素的高度：
  // 窗口尺寸变化（转屏、iOS 浏览器工具栏伸缩）的瞬间页面还没重新排版，读到的是旧高度，画面会偏
  const h = winSize.value.h
  const offset = -h + dragY.value - sliding.value * h
  return {
    transform: `translate3d(0, ${offset}px, 0)`,
    transition: animating.value ? 'transform 0.28s cubic-bezier(0.2, 0.7, 0.3, 1)' : 'none',
  }
})

function go(dir: 1 | -1) {
  const next = cur.value + dir
  if (animating.value) return
  if (next < 0 || next >= eps.value.length) {
    message.info(dir > 0 ? t('已经是最后一集了') : t('已经是第一集了'))
    return snapBack()
  }
  animating.value = true
  sliding.value = dir
  dragY.value = 0
  setTimeout(() => {
    animating.value = false
    sliding.value = 0
    load(next, 0)
  }, 290)
}

function snapBack() {
  animating.value = true
  dragY.value = 0
  setTimeout(() => (animating.value = false), 290)
}

let touchY = 0
let tracking = false
let touchT = 0
let touchMoved = false
function onTouchStart(e: TouchEvent) {
  if (animating.value || e.touches.length !== 1) return
  touchY = e.touches[0].clientY
  tracking = true
  touchT = Date.now()
  touchMoved = false
}
function onTouchMove(e: TouchEvent) {
  if (animating.value || !tracking) return
  let dy = e.touches[0].clientY - touchY
  if (Math.abs(dy) > 8) touchMoved = true
  // 第一集 / 最后一集时增加阻力
  if ((dy > 0 && cur.value === 0) || (dy < 0 && cur.value === eps.value.length - 1)) dy /= 3
  dragY.value = dy
}
function onTouchEnd() {
  if (!tracking) return
  const dy = dragY.value
  const fast = Math.abs(dy) / Math.max(1, Date.now() - touchT) > 0.5
  tracking = false
  if (!touchMoved) return
  const h = stage.value?.clientHeight || window.innerHeight
  if (dy < -h * 0.18 || (fast && dy < -40)) go(1)
  else if (dy > h * 0.18 || (fast && dy > 40)) go(-1)
  else snapBack()
}

function onClickVideo() {
  // 滑动、长按倍速之后浏览器仍会触发一次点击
  if (touchMoved || suppressClick) {
    touchMoved = false
    suppressClick = false
    return
  }
  toggle()
}

// ---------------- 长按倍速（与抖音相同：按住 2 倍速，松开恢复） ----------------

const boosting = ref(false)
let pressTimer: ReturnType<typeof setTimeout> | null = null
let pressX = 0
let pressY = 0
let suppressClick = false
/** 长按时的倍速：平时不到 2 倍用 2 倍，已经 2 倍及以上再快一档 */
const boostRate = () => (rate.value < 2 ? 2 : 3)

function onPressStart(e: PointerEvent) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  cancelPress()
  pressX = e.clientX
  pressY = e.clientY
  pressTimer = setTimeout(() => {
    pressTimer = null
    const el = video.value
    if (!el || error.value) return
    boosting.value = true
    el.playbackRate = boostRate()
    if (el.paused) play()
  }, 450)
}

function onPressMove(e: PointerEvent) {
  // 移动了就是在滑动切集，不是长按
  if (pressTimer && Math.hypot(e.clientX - pressX, e.clientY - pressY) > 10) cancelPress()
}

function onPressEnd() {
  cancelPress()
  if (!boosting.value) return
  boosting.value = false
  suppressClick = true
  setTimeout(() => (suppressClick = false), 400)
  if (video.value) video.value.playbackRate = rate.value
}

function cancelPress() {
  if (pressTimer) clearTimeout(pressTimer)
  pressTimer = null
}

// ---------------- 横屏视频 ----------------

/** 当前视频是横屏的（宽大于高） */
const isLandscape = ref(false)
/**
 * 视频尺寸变化时重新判断。iOS Safari 播放 m3u8 时，读取到视频信息那一刻往往还不知道画面尺寸（为 0），
 * 要等到 resize 事件才有，只在 loadedmetadata 判断会认不出横屏视频
 */
function updateOrientation() {
  const el = video.value
  if (el?.videoWidth && el.videoHeight) isLandscape.value = el.videoWidth > el.videoHeight * 1.05
}
const isFullscreen = ref(false)
const watchEl = ref<HTMLElement | null>(null)

/** iPhone / iPad（iPadOS 的 Safari 会伪装成 Mac） */
const isIOS = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)

function toggleFullscreen() {
  const el = watchEl.value
  const v = video.value as (HTMLVideoElement & { webkitEnterFullscreen?: () => void }) | null
  if (!el || !v) return
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {})
    return
  }
  // iOS：iPhone 不支持网页元素全屏、网页也隐藏不了浏览器栏，直接用系统播放器全屏
  // （横屏跟随手机的实际方向）。必须在点击时同步调用，中间不能有 await，否则会被拒绝
  if (isIOS && v.webkitEnterFullscreen) {
    v.webkitEnterFullscreen()
    return
  }
  if (el.requestFullscreen) {
    el.requestFullscreen()
      .then(() => {
        // 横屏视频全屏时转为横屏（Android Chrome 支持；不支持时忽略）
        const orientation = screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> }
        if (isLandscape.value) orientation.lock?.('landscape').catch(() => {})
      })
      .catch(() => v.webkitEnterFullscreen?.())
    return
  }
  v.webkitEnterFullscreen?.()
}

/** 窗口尺寸（播放区域高度按它计算） */
const winSize = ref({ w: window.innerWidth, h: window.innerHeight })
function onWinResize() {
  winSize.value = { w: window.innerWidth, h: window.innerHeight }
}
window.addEventListener('resize', onWinResize)
onBeforeUnmount(() => window.removeEventListener('resize', onWinResize))

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
  if (!isFullscreen.value) (screen.orientation as ScreenOrientation & { unlock?: () => void }).unlock?.()
}
document.addEventListener('fullscreenchange', onFullscreenChange)
onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
})

let wheelAcc = 0
let wheelLock = 0
function onWheel(e: WheelEvent) {
  if (showEpisodes.value) return
  if (Date.now() < wheelLock) return
  wheelAcc += e.deltaY
  if (Math.abs(wheelAcc) > 60) {
    go(wheelAcc > 0 ? 1 : -1)
    wheelAcc = 0
    wheelLock = Date.now() + 700
  }
}

function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  if (e.key === 'ArrowDown' || e.key === 'j') go(1)
  else if (e.key === 'ArrowUp' || e.key === 'k') go(-1)
  else if (e.key === ' ') {
    e.preventDefault()
    toggle()
  } else if (e.key === 'ArrowRight' && video.value) video.value.currentTime += 5
  else if (e.key === 'ArrowLeft' && video.value) video.value.currentTime -= 5
  else return
  e.preventDefault()
}

// ---------------- 进度条 ----------------

const draggingBar = ref(false)
const bar = ref<HTMLElement | null>(null)
function barRatio(e: PointerEvent) {
  const r = bar.value!.getBoundingClientRect()
  const v = (e.clientX - r.left) / r.width
  return Math.max(0, Math.min(1, v))
}
function onBarDown(e: PointerEvent) {
  if (!duration.value) return
  draggingBar.value = true
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  position.value = barRatio(e) * duration.value
}
function onBarMove(e: PointerEvent) {
  if (draggingBar.value) position.value = barRatio(e) * duration.value
}
function onBarUp() {
  if (!draggingBar.value) return
  draggingBar.value = false
  if (video.value) video.value.currentTime = position.value
}

// ---------------- 选集 ----------------

const showEpisodes = ref(false)
const epGrid = ref<HTMLElement | null>(null)
/** 打开选集时滚动到当前集（放在可视区域中间） */
function scrollToCurrentEpisode() {
  epGrid.value?.querySelector<HTMLElement>('.ep-item.active')?.scrollIntoView({ block: 'center' })
}
// 内容渲染出来就滚动，不等弹出动画结束（动画事件在部分设备上不可靠）
watch(showEpisodes, async (v) => {
  if (!v) return
  await nextTick()
  setTimeout(scrollToCurrentEpisode, 50)
})
function pick(i: number) {
  showEpisodes.value = false
  if (i !== cur.value) load(i, 0)
}
function epLabel(c: Chapter, i: number) {
  const m = c.title.match(/(\d+)/)
  return m && c.title.length <= 12 ? m[1] : String(i + 1)
}

// ---------------- 进度 ----------------

let lastSave = 0
function saveProgress(keepalive = false) {
  const ep = episode.value
  if (!book.value || !ep) return
  lastSave = Date.now()
  const pos = Math.round((video.value?.currentTime ?? position.value) * 1000)
  if (keepalive) {
    fetch(`/api/shelf/${bookId.value}/progress`, {
      method: 'PUT',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ index: ep.index, pos, title: ep.title, time: Date.now() }),
    }).catch(() => {})
  } else {
    shelfApi.progress(bookId.value, ep.index, pos, ep.title, Date.now()).catch(() => {})
  }
}
function onBeforeUnload() {
  saveProgress(true)
}

function back() {
  if (window.history.state?.back) router.back()
  else router.push({ name: 'book', params: { id: bookId.value } })
}
</script>

<template>
  <div ref="watchEl" class="watch" @wheel.passive="onWheel">
    <div
      ref="stage"
      class="stage"
      :class="{ wide: isLandscape }"
      @touchstart.passive="onTouchStart"
      @touchmove.passive="onTouchMove"
      @touchend="onTouchEnd"
      @touchcancel="onTouchEnd"
    >
      <div class="track" :style="trackStyle">
        <!-- 上一集 / 下一集只显示封面，切换后加载视频 -->
        <div class="slide">
          <div v-if="eps[cur - 1]" class="poster" :style="cover ? { backgroundImage: `url(${cover})` } : {}">
            <span>{{ eps[cur - 1].title }}</span>
          </div>
        </div>
        <div class="slide">
          <video
            ref="video"
            class="video"
            playsinline
            webkit-playsinline
            preload="auto"
            :poster="cover || undefined"
            @click="onClickVideo"
            @pointerdown="onPressStart"
            @pointermove="onPressMove"
            @pointerup="onPressEnd"
            @pointercancel="onPressEnd"
            @pointerleave="onPressEnd"
            @contextmenu.prevent
            @loadedmetadata="onLoadedMetadata"
            @resize="updateOrientation"
            @loadeddata="updateOrientation"
            @timeupdate="onTimeUpdate"
            @durationchange="duration = video?.duration || 0"
            @play="playing = true"
            @playing="buffering = false"
            @pause="playing = false; saveProgress()"
            @waiting="buffering = true"
            @canplay="buffering = false"
            @ended="onEnded"
            @error="onVideoError"
          />
          <div v-if="!playing && !buffering && !error && !loading" class="center-play" @click="toggle">
            <n-icon :component="Play" size="64" />
          </div>
          <div v-if="buffering && !error" class="center-spin"><n-spin size="large" /></div>
          <div v-if="boosting" class="boost-tip"><n-icon :component="PlayForward" size="16" /> {{ t('{0}x 快速播放中', [boostRate()]) }}</div>
          <div v-if="error" class="center-error" @click="toggle">
            <div>{{ error }}</div>
            <div class="retry">{{ t('点击重试') }}</div>
          </div>
        </div>
        <div class="slide">
          <div v-if="eps[cur + 1]" class="poster" :style="cover ? { backgroundImage: `url(${cover})` } : {}">
            <span>{{ eps[cur + 1].title }}</span>
          </div>
        </div>
      </div>

      <!-- 顶部 -->
      <div class="top">
        <button class="round" :title="t('返回')" @click="back"><n-icon :component="ArrowBackOutline" size="22" /></button>
      </div>

      <!-- 右侧操作 -->
      <div class="actions">
        <button v-if="muted" class="act" :title="t('取消静音')" @click="toggle">
          <n-icon :component="VolumeMuteOutline" size="28" /><span>{{ t('静音中') }}</span>
        </button>
        <button v-if="isLandscape || isFullscreen" class="act" :title="isFullscreen ? t('退出全屏') : t('全屏（横屏播放）')" @click="toggleFullscreen">
          <n-icon :component="isFullscreen ? ContractOutline : ExpandOutline" size="28" /><span>{{ isFullscreen ? t('退出') : t('全屏') }}</span>
        </button>
        <button class="act" :title="t('选集')" @click="showEpisodes = true">
          <n-icon :component="AlbumsOutline" size="28" /><span>{{ t('选集') }}</span>
        </button>
        <n-dropdown :options="rateOptions" trigger="click" placement="left" @select="(k: number) => (rate = k)">
          <button class="act" :title="t('倍速')">
            <n-icon :component="SpeedometerOutline" size="28" /><span>{{ rate }}x</span>
          </button>
        </n-dropdown>
      </div>

      <!-- 底部信息和进度 -->
      <div class="bottom">
        <div class="name">{{ book?.name }}</div>
        <div class="ep">
          {{ episode?.title }}<span v-if="eps.length" class="count"> {{ t('· 第 {0} 集 / 共 {1} 集', [cur + 1, eps.length]) }}</span>
        </div>
        <div v-if="book?.customIntro || book?.intro" class="intro">{{ book?.customIntro || book?.intro }}</div>
        <div
          ref="bar"
          class="bar"
          :class="{ active: draggingBar }"
          @pointerdown="onBarDown"
          @pointermove="onBarMove"
          @pointerup="onBarUp"
          @pointercancel="onBarUp"
          @touchstart.stop
          @touchmove.stop
          @touchend.stop
        >
          <div class="bar-fill" :style="{ width: duration ? `${(position / duration) * 100}%` : '0' }" />
        </div>
        <div v-if="draggingBar" class="drag-time">{{ fmt(position) }} / {{ fmt(duration) }}</div>
      </div>
    </div>

    <n-drawer v-model:show="showEpisodes" placement="bottom" height="60%" :auto-focus="false">
      <n-drawer-content :title="t('选集 · 共 {0} 集', [eps.length])" :native-scrollbar="false">
        <div ref="epGrid" class="ep-grid">
          <button
            v-for="(c, i) in eps"
            :key="c.index"
            class="ep-item"
            :class="{ active: i === cur }"
            :title="c.title"
            @click="pick(i)"
          >{{ epLabel(c, i) }}</button>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.watch {
  position: fixed;
  inset: 0;
  background: #000;
  color: #fff;
  overflow: hidden;
  user-select: none;
  -webkit-user-select: none;
}

/* 竖屏短剧：宽屏上按 9:16 居中 */
.stage {
  position: relative;
  height: 100%;
  width: 100%;
  margin: 0 auto;
  overflow: hidden;
  touch-action: none;
}

/* 横屏视频：宽屏上铺满宽度 */
.stage.wide {
  width: 100% !important;
}

.boost-tip {
  position: absolute;
  top: calc(64px + env(safe-area-inset-top));
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: 16px;
  background: rgba(0, 0, 0, 0.55);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
  pointer-events: none;
  white-space: nowrap;
}

@media (min-width: 768px) {
  .stage {
    width: min(100vw, max(56.25vh, 420px));
  }
}

.track {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 300%;
  display: flex;
  flex-direction: column;
  will-change: transform;
}

.slide {
  position: relative;
  flex: 1;
  /* 横屏视频在宽的播放区域里按比例的高度可能超过一屏：不让视频撑高滑块 */
  min-height: 0;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
  /* 长按时不弹出系统的“保存视频”菜单 */
  -webkit-touch-callout: none;
  user-select: none;
}

.poster {
  position: absolute;
  inset: 0;
  background: #111 center / cover no-repeat;
  display: flex;
  align-items: center;
  justify-content: center;
}

.poster::before {
  content: '';
  position: absolute;
  inset: 0;
  backdrop-filter: blur(18px) brightness(0.5);
}

.poster span {
  position: relative;
  font-size: 18px;
  font-weight: 600;
}

.center-play,
.center-spin,
.center-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.center-play {
  opacity: 0.8;
  cursor: pointer;
}

.center-spin {
  pointer-events: none;
}

.center-error {
  padding: 0 32px;
  text-align: center;
  font-size: 14px;
  cursor: pointer;
}

.retry {
  padding: 6px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.18);
}

.top {
  position: absolute;
  top: calc(10px + env(safe-area-inset-top));
  left: 10px;
  z-index: 2;
}

.round {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.actions {
  position: absolute;
  right: 8px;
  bottom: 150px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  z-index: 2;
}

.act {
  border: none;
  background: none;
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  cursor: pointer;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.6);
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.5));
}

.bottom {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 40px 72px calc(14px + env(safe-area-inset-bottom)) 14px;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.55));
  z-index: 2;
  pointer-events: none;
}

.name {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 4px;
}

.ep {
  font-size: 14px;
}

.count {
  opacity: 0.75;
}

.intro {
  margin-top: 4px;
  font-size: 12.5px;
  opacity: 0.75;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar {
  margin: 12px -58px 0 0;
  height: 16px;
  display: flex;
  align-items: center;
  cursor: pointer;
  pointer-events: auto;
  touch-action: none;
  position: relative;
}

.bar::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: rgba(255, 255, 255, 0.25);
  border-radius: 2px;
}

.bar-fill {
  position: relative;
  height: 2px;
  background: #fff;
  border-radius: 2px;
}

.bar.active::before,
.bar.active .bar-fill {
  height: 4px;
}

.drag-time {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 60px;
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ep-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(56px, 1fr));
  gap: 10px;
}

.ep-item {
  height: 44px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  color: var(--app-text);
  cursor: pointer;
  font-size: 14px;
}

.ep-item.active {
  border-color: var(--app-accent);
  color: var(--app-accent);
  font-weight: 700;
}
</style>
