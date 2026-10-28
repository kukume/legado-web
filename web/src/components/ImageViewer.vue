<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { NIcon, useMessage } from 'naive-ui'
import { AddOutline, Close, DownloadOutline, RemoveOutline, ScanOutline } from '@vicons/ionicons5'
import { t } from '@/i18n'

/**
 * 正文图片查看：全屏显示，滚轮 / 双指缩放、双击放大还原、拖动平移，可以保存图片。
 * Esc 或点空白处关闭
 */
const props = defineProps<{ src: string }>()
const show = defineModel<boolean>('show', { default: false })
const message = useMessage()

const MIN = 1
const MAX = 6
const view = reactive({ scale: 1, x: 0, y: 0 })
const imgEl = ref<HTMLImageElement | null>(null)
const failed = ref(false)

function reset() {
  view.scale = 1
  view.x = 0
  view.y = 0
}
watch(() => [show.value, props.src], () => {
  reset()
  failed.value = false
})

const transform = computed(() => `translate(${view.x}px, ${view.y}px) scale(${view.scale})`)

/** 以 (cx, cy)（相对画面中心）为中心缩放到 next */
function zoomTo(next: number, cx = 0, cy = 0) {
  const s = Math.min(MAX, Math.max(MIN, next))
  const k = s / view.scale
  view.x = cx - (cx - view.x) * k
  view.y = cy - (cy - view.y) * k
  view.scale = s
  if (s === MIN) reset()
}

function center(e: { clientX: number; clientY: number }) {
  return { cx: e.clientX - window.innerWidth / 2, cy: e.clientY - window.innerHeight / 2 }
}

function onWheel(e: WheelEvent) {
  const { cx, cy } = center(e)
  zoomTo(view.scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), cx, cy)
}

function onDblClick(e: MouseEvent) {
  const { cx, cy } = center(e)
  zoomTo(view.scale > 1 ? 1 : 2.5, cx, cy)
}

// ---------- 拖动、双指缩放 ----------
const pointers = new Map<number, { x: number; y: number }>()
let pinch: { dist: number; scale: number } | null = null
let moved = false
let lastTap = 0

function onPointerDown(e: PointerEvent) {
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  moved = false
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinch = { dist: Math.hypot(a.x - b.x, a.y - b.y), scale: view.scale }
  }
}

function onPointerMove(e: PointerEvent) {
  const prev = pointers.get(e.pointerId)
  if (!prev) return
  const cur = { x: e.clientX, y: e.clientY }
  pointers.set(e.pointerId, cur)
  if (pointers.size === 2 && pinch) {
    const [a, b] = [...pointers.values()]
    const { cx, cy } = center({ clientX: (a.x + b.x) / 2, clientY: (a.y + b.y) / 2 })
    zoomTo(pinch.scale * (Math.hypot(a.x - b.x, a.y - b.y) / pinch.dist), cx, cy)
    moved = true
  } else if (pointers.size === 1 && view.scale > 1) {
    view.x += cur.x - prev.x
    view.y += cur.y - prev.y
    if (Math.abs(cur.x - prev.x) + Math.abs(cur.y - prev.y) > 1) moved = true
  }
}

function onPointerUp(e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinch = null
  // 触摸屏双击放大（鼠标的双击由 dblclick 处理）
  if (e.pointerType === 'touch' && !moved && pointers.size === 0) {
    const now = Date.now()
    if (now - lastTap < 300) {
      onDblClick(e)
      lastTap = 0
    } else lastTap = now
  }
}

/**
 * 点图片外的空白处关闭（拖动、缩放后松手不算）。
 * 按图片的实际范围判断：拖动时捕获了指针，click 的 target 总是外层
 */
function onBackdropClick(e: MouseEvent) {
  if (moved) return
  const r = imgEl.value?.getBoundingClientRect()
  const inside = r && e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom
  if (!inside) show.value = false
}

async function save() {
  try {
    const res = await fetch(props.src)
    if (!res.ok) throw new Error(String(res.status))
    const blob = await res.blob()
    const ext = (blob.type.split('/')[1] || 'jpg').replace('jpeg', 'jpg').replace(/\+.*/, '')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `image-${Date.now()}.${ext}`
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  } catch {
    message.error(t('保存图片失败'))
  }
}

function onKey(e: KeyboardEvent) {
  if (!show.value) return
  if (e.key === 'Escape') show.value = false
  else if (e.key === '+' || e.key === '=') zoomTo(view.scale * 1.25)
  else if (e.key === '-') zoomTo(view.scale / 1.25)
  else if (e.key === '0') reset()
  else return
  e.preventDefault()
  e.stopPropagation()
}
// 捕获阶段处理，避免按键同时触发阅读页的翻页快捷键
window.addEventListener('keydown', onKey, true)
onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true))
</script>

<template>
  <Teleport to="body">
    <transition name="iv-fade">
      <div
        v-if="show"
        class="image-viewer"
        @click="onBackdropClick"
        @wheel.prevent="onWheel"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
      >
        <img
          v-if="!failed"
          ref="imgEl"
          :src="src"
          alt=""
          draggable="false"
          :style="{ transform }"
          :class="{ zoomed: view.scale > 1 }"
          @dblclick.stop="onDblClick"
          @error="failed = true"
        />
        <div v-else class="iv-error">{{ t('图片加载失败') }}</div>

        <div class="iv-toolbar" @click.stop @pointerdown.stop>
          <button :title="t('缩小')" @click="zoomTo(view.scale / 1.25)"><n-icon :component="RemoveOutline" size="20" /></button>
          <span class="iv-scale">{{ Math.round(view.scale * 100) }}%</span>
          <button :title="t('放大')" @click="zoomTo(view.scale * 1.25)"><n-icon :component="AddOutline" size="20" /></button>
          <button :title="t('还原')" @click="reset"><n-icon :component="ScanOutline" size="20" /></button>
          <button :title="t('保存图片')" @click="save"><n-icon :component="DownloadOutline" size="20" /></button>
          <button :title="t('关闭')" @click="show = false"><n-icon :component="Close" size="20" /></button>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.image-viewer {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.88);
  touch-action: none;
  user-select: none;
}

.image-viewer img {
  max-width: 100vw;
  max-height: 100vh;
  object-fit: contain;
  transition: transform 0.12s ease-out;
  cursor: zoom-in;
}

.image-viewer img.zoomed {
  cursor: grab;
}

.iv-error {
  color: #ddd;
  font-size: 14px;
}

.iv-toolbar {
  position: absolute;
  left: 50%;
  bottom: calc(20px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 6px;
  border-radius: 22px;
  background: rgba(40, 40, 40, 0.85);
  color: #eee;
}

.iv-toolbar button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: none;
  color: inherit;
  cursor: pointer;
}

.iv-toolbar button:hover {
  background: rgba(255, 255, 255, 0.12);
}

.iv-scale {
  min-width: 44px;
  text-align: center;
  font-size: 12px;
  opacity: 0.8;
}

.iv-fade-enter-active,
.iv-fade-leave-active {
  transition: opacity 0.15s;
}

.iv-fade-enter-from,
.iv-fade-leave-to {
  opacity: 0;
}
</style>
