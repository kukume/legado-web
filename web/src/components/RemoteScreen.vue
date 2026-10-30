<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { NButton, NIcon, NSpin, useMessage } from 'naive-ui'
import { ArrowBackOutline, ArrowForwardOutline, RefreshOutline, KeypadOutline, DesktopOutline, PhonePortraitOutline } from '@vicons/ionicons5'
import { getToken } from '@/api/http'
import { t } from '@/i18n'

/**
 * 远程浏览器：网页在服务器上的浏览器里打开，这里显示实时画面，
 * 并把点击、滚动、输入转发给浏览器（坐标按网页视口的 CSS 像素换算）
 */
const props = defineProps<{ token: string }>()
/** 切换手机版 / 电脑版（电脑版时弹窗加宽） */
const emit = defineEmits<{ (e: 'mode', desktop: boolean): void }>()
const message = useMessage()

interface Frame {
  data: string
  width: number
  height: number
}

const stage = ref<HTMLElement | null>(null)
const kb = ref<HTMLTextAreaElement | null>(null)
const frame = ref<Frame | null>(null)
const url = ref('')
const error = ref('')
/** 排队中：排在第几位（同一时间只打开一个网页），0 表示没有排队 */
const queue = ref(0)
/** 电脑版：网页按电脑屏幕排版（识别到电脑版网页时服务器自动切换，也可以手动切换） */
const desktop = ref(false)
const touch = typeof window !== 'undefined' && matchMedia('(pointer: coarse)').matches

let ws: WebSocket | null = null

function connect() {
  const el = stage.value
  const w = Math.round(el?.clientWidth || 400)
  const h = Math.round(el?.clientHeight || 600)
  // 网页视口跟着画面区域的大小；令牌经 ?token= 传（WebSocket 不能自定义请求头）
  const q = new URLSearchParams({ token: getToken(), w: String(w), h: String(h) })
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/api/browser/${encodeURIComponent(props.token)}/screen?${q}`)
  ws.onmessage = (e) => {
    let d: Record<string, string>
    try {
      d = JSON.parse(e.data)
    } catch {
      return
    }
    if (d.k === 'f') {
      // 画面帧
      frame.value = { data: d.data, width: Number(d.width), height: Number(d.height) }
      error.value = ''
    } else if (d.k === 'i') {
      // 通知
      if (d.type === 'url') url.value = d.value
      else if (d.type === 'mode') {
        desktop.value = d.value === 'desktop'
        emit('mode', desktop.value)
      } else if (d.type === 'auto') message.info(t('这是电脑版网页，已切换到电脑版'))
      else if (d.type === 'queue') queue.value = Number(d.value) || 0
      else if (d.type === 'ready') queue.value = 0
      else if (d.type === 'error') {
        error.value = d.value
        ws?.close()
      } else if (d.type === 'dialog' && d.value) message.info(t('网页提示：{0}', [d.value]))
      else if (d.type === 'blocked') message.warning(t('只能打开书源自己网站的网页，已阻止跳转到 {0}', [d.value]))
      else if (d.type === 'app') message.warning(t('网页想打开手机 App（{0}），远程浏览器中无法打开，已忽略', [d.value]))
    }
  }
  ws.onerror = () => {
    if (!frame.value && !error.value) error.value = t('无法连接到服务器上的浏览器')
  }
}

/** 画面区域大小变化（调整窗口、旋转屏幕）时，网页视口跟着变 */
let resizeTimer: ReturnType<typeof setTimeout> | null = null
let lastSize = ''
const ro = typeof ResizeObserver !== 'undefined'
  ? new ResizeObserver(() => {
      if (resizeTimer) clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        const el = stage.value
        if (!el || !el.clientWidth || !el.clientHeight) return
        const size = `${el.clientWidth}x${el.clientHeight}`
        if (size === lastSize) return
        lastSize = size
        send({ type: 'resize', width: el.clientWidth, height: el.clientHeight })
      }, 300)
    })
  : null

onMounted(() =>
  nextTick(() => {
    connect()
    if (stage.value) {
      lastSize = `${stage.value.clientWidth}x${stage.value.clientHeight}`
      ro?.observe(stage.value)
    }
  }),
)
onBeforeUnmount(() => {
  ws?.close()
  ro?.disconnect()
  if (resizeTimer) clearTimeout(resizeTimer)
})

// ---------------- 转发操作（和画面走同一条 WebSocket） ----------------

function send(input: Record<string, unknown>) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(input))
}

/** 屏幕坐标换算成网页视口的坐标 */
function pos(e: { clientX: number; clientY: number }) {
  const img = stage.value?.querySelector('img')
  const f = frame.value
  if (!img || !f) return null
  // 画面按比例缩放、水平居中靠上显示（object-fit: contain），减去左右留白后按缩放比例换算
  const r = img.getBoundingClientRect()
  const s = viewScale()
  const offsetX = (r.width - f.width * s) / 2
  return { x: (e.clientX - r.left - offsetX) / s, y: (e.clientY - r.top) / s }
}

/** 屏幕像素 / 网页 CSS 像素 */
function viewScale() {
  const img = stage.value?.querySelector('img')
  const f = frame.value
  if (!img || !f) return 1
  const r = img.getBoundingClientRect()
  return Math.min(r.width / f.width, r.height / f.height) || 1
}

let lastMove = 0
/** 触摸：没有移动就是点击，移动了就是滚动 */
let touchStart: { x: number; y: number; cx: number; cy: number; moved: boolean } | null = null

function onPointerDown(e: PointerEvent) {
  const p = pos(e)
  if (!p) return
  ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
  if (e.pointerType === 'touch') {
    touchStart = { ...p, cx: e.clientX, cy: e.clientY, moved: false }
    return
  }
  send({ type: 'mouse', action: 'down', ...p })
  // 电脑上点击后，键盘输入发给网页
  if (!touch) kb.value?.focus({ preventScroll: true })
}

function onPointerMove(e: PointerEvent) {
  const now = Date.now()
  if (e.pointerType === 'touch') {
    const s = touchStart
    if (!s) return
    const dx = e.clientX - s.cx
    const dy = e.clientY - s.cy
    if (!s.moved && Math.hypot(dx, dy) < 8) return
    s.moved = true
    if (now - lastMove < 40) return
    lastMove = now
    const scale = 1 / viewScale()
    send({ type: 'wheel', x: s.x, y: s.y, dx: -dx * scale, dy: -dy * scale })
    s.cx = e.clientX
    s.cy = e.clientY
    return
  }
  // 鼠标移动（悬停效果、拖动）：限制频率
  if (now - lastMove < 50) return
  lastMove = now
  const p = pos(e)
  if (p) send({ type: 'mouse', action: 'move', ...p })
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerType === 'touch') {
    const s = touchStart
    touchStart = null
    if (s && !s.moved) {
      send({ type: 'mouse', action: 'down', x: s.x, y: s.y })
      send({ type: 'mouse', action: 'up', x: s.x, y: s.y })
    }
    return
  }
  const p = pos(e)
  if (p) send({ type: 'mouse', action: 'up', ...p })
}

function onWheel(e: WheelEvent) {
  const p = pos(e)
  if (!p) return
  send({ type: 'wheel', ...p, dx: e.deltaX, dy: e.deltaY })
}

// ---------------- 键盘（隐藏的输入框接收输入，支持中文输入法） ----------------

const keyCodes: Record<string, number> = {
  Enter: 13, Backspace: 8, Tab: 9, Escape: 27, Delete: 46,
  ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Home: 36, End: 35, PageUp: 33, PageDown: 34,
}
let composing = false

function onKeyDown(e: KeyboardEvent) {
  if (composing || e.isComposing) return
  const code = keyCodes[e.key]
  if (code) {
    e.preventDefault()
    send({ type: 'key', key: e.key, code: e.code || e.key, keyCode: code })
  }
}

function onInput() {
  if (composing || !kb.value) return
  const text = kb.value.value
  kb.value.value = ''
  if (text) send({ type: 'text', text })
}

function onCompositionEnd(e: CompositionEvent) {
  composing = false
  if (kb.value) kb.value.value = ''
  if (e.data) send({ type: 'text', text: e.data })
}

function onPaste(e: ClipboardEvent) {
  const text = e.clipboardData?.getData('text')
  e.preventDefault()
  if (text) send({ type: 'text', text })
}

function toggleDesktop() {
  send({ type: 'viewMode', action: desktop.value ? 'mobile' : 'desktop' })
}

function showKeyboard() {
  kb.value?.focus()
}
</script>

<template>
  <div class="remote">
    <div class="bar">
      <n-button size="tiny" quaternary :title="t('后退')" @click="send({ type: 'back' })">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <n-button size="tiny" quaternary :title="t('前进')" @click="send({ type: 'forward' })">
        <template #icon><n-icon :component="ArrowForwardOutline" /></template>
      </n-button>
      <n-button size="tiny" quaternary :title="t('刷新')" @click="send({ type: 'reload' })">
        <template #icon><n-icon :component="RefreshOutline" /></template>
      </n-button>
      <span class="mono ellipsis url" :title="url">{{ url }}</span>
      <n-button size="tiny" quaternary :title="desktop ? t('切换到手机版') : t('切换到电脑版')" @click="toggleDesktop">
        <template #icon><n-icon :component="desktop ? PhonePortraitOutline : DesktopOutline" /></template>
      </n-button>
      <n-button v-if="touch" size="tiny" quaternary :title="t('输入文字')" @click="showKeyboard">
        <template #icon><n-icon :component="KeypadOutline" /></template>
      </n-button>
    </div>
    <div
      ref="stage"
      class="stage"
      @pointerdown.prevent="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="touchStart = null"
      @wheel.prevent="onWheel"
      @contextmenu.prevent
    >
      <img v-if="frame" :src="`data:image/jpeg;base64,${frame.data}`" alt="" draggable="false" />
      <div v-else class="center">
        <span v-if="error" class="err">{{ error }}</span>
        <div v-else-if="queue" class="queue">
          <n-spin size="small" />
          <span>{{ t('服务器上的浏览器正在被使用，排队中：前面还有 {0} 个网页', [queue]) }}</span>
        </div>
        <n-spin v-else size="small" />
      </div>
      <textarea
        ref="kb"
        class="kb"
        autocapitalize="off"
        autocomplete="off"
        spellcheck="false"
        @keydown="onKeyDown"
        @input="onInput"
        @compositionstart="composing = true"
        @compositionend="onCompositionEnd"
        @paste="onPaste"
      />
    </div>
    <p class="muted small tip">{{ t('网页在服务器上的浏览器中打开，可以直接点击、滚动和输入；完成验证或登录后点“完成”。') }}</p>
  </div>
</template>

<style scoped>
.remote {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bar {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
}

.url {
  flex: 1;
  min-width: 0;
  opacity: 0.7;
  padding: 0 6px;
}

.stage {
  position: relative;
  /* 网页是手机屏幕（412×860）：电脑上按高度缩放、居中显示，两侧留白 */
  height: min(76vh, 860px);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  overflow: hidden;
  background: color-mix(in srgb, currentColor 5%, transparent);
  touch-action: none;
  user-select: none;
  cursor: default;
}

.stage img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  object-position: top center;
  pointer-events: none;
}

.center {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.err {
  color: #d03050;
  font-size: 13px;
  padding: 0 16px;
  text-align: center;
}

.queue {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  font-size: 13px;
  text-align: center;
  color: var(--app-text-2);
}

/* 接收键盘输入的输入框：不可见，但能获得焦点 */
.kb {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 1px;
  height: 1px;
  opacity: 0;
  border: 0;
  padding: 0;
  resize: none;
  font-size: 16px;
}

.small {
  font-size: 12px;
}

.tip {
  margin: 0;
}

@media (max-width: 767px) {
  .stage {
    height: calc(100dvh - 230px);
  }
}
</style>
