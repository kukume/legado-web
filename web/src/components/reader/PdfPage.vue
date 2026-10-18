<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { bookPdf, cachedRatio, pageRatio, renderPage } from '@/utils/pdf'
import { t } from '@/i18n'

/**
 * PDF 的一页：先按页面比例占位，进入可视范围附近时才渲染（宽度跟随容器，按设备像素比放大）
 */
const props = defineProps<{ bookId: number; page: number }>()
const emit = defineEmits<{ load: [] }>()

const canvas = ref<HTMLCanvasElement | null>(null)
/** 高 / 宽；未知时按 A4 占位 */
const ratio = ref(cachedRatio(props.bookId, props.page) ?? 1.414)
const error = ref('')
const rendered = ref(false)

let visible = false
let renderedWidth = 0
let rendering: { cancelled: boolean } | null = null
let io: IntersectionObserver | null = null
let ro: ResizeObserver | null = null

async function render() {
  const el = canvas.value
  if (!el || !visible) return
  const width = el.clientWidth
  if (!width || (rendered.value && Math.abs(width - renderedWidth) < 24)) return
  if (rendering) rendering.cancelled = true
  const signal = (rendering = { cancelled: false })
  try {
    await renderPage(await bookPdf(props.bookId), props.page, el, width, signal)
    if (signal.cancelled) return
    renderedWidth = width
    rendered.value = true
    error.value = ''
  } catch (e: any) {
    if (!signal.cancelled) error.value = e?.message ?? String(e)
  } finally {
    if (rendering === signal) rendering = null
  }
}

/** 离开可视范围后释放画布（每页的画布有好几 MB，滚动模式会同时保留几十页），回来时重新渲染；占位高度由 aspect-ratio 保持 */
function release() {
  if (rendering) rendering.cancelled = true
  rendering = null
  const el = canvas.value
  if (!el || !rendered.value) return
  el.width = 0
  el.height = 0
  rendered.value = false
  renderedWidth = 0
}

onMounted(async () => {
  try {
    ratio.value = await pageRatio(props.bookId, props.page)
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  }
  emit('load')
  if (!canvas.value) return
  io = new IntersectionObserver(
    (entries) => {
      visible = entries.some((e) => e.isIntersecting)
      if (visible) render()
      else release()
    },
    // 以滚动容器为参照（以视口为参照时会被滚动容器裁剪，预先渲染的边距不起作用）
    { root: canvas.value.closest('.scroller'), rootMargin: '1200px 0px' },
  )
  io.observe(canvas.value)
  // 窗口宽度变化（旋转屏幕、调整窗口）后按新宽度重新渲染
  ro = new ResizeObserver(() => render())
  ro.observe(canvas.value)
})

onBeforeUnmount(() => {
  if (rendering) rendering.cancelled = true
  io?.disconnect()
  ro?.disconnect()
})
</script>

<template>
  <div class="pdf-page-wrap">
    <canvas ref="canvas" class="para-img full pdf-page" :class="{ blank: !rendered }" :style="{ aspectRatio: `1 / ${ratio}` }" />
    <div v-if="error" class="pdf-error">{{ t('第 {0} 页渲染失败：{1}', [page + 1, error]) }}</div>
  </div>
</template>

<style scoped>
.pdf-page-wrap {
  position: relative;
  break-inside: avoid;
}

.pdf-page {
  display: block;
  width: 100%;
  max-width: 100%;
  margin: 0.5em auto;
  border-radius: 4px;
  height: auto;
  object-fit: contain;
  background: #fff;
}

/* 未渲染时的占位 */
.pdf-page.blank {
  background: color-mix(in srgb, currentColor 6%, transparent);
}

.pdf-error {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  font-size: 13px;
  text-align: center;
  opacity: 0.7;
}
</style>
