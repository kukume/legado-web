import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { t } from '@/i18n'

// 旧版 Safari（iPad）只有带 webkit 前缀的全屏接口
type WebkitDocument = Document & { webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => void }
type WebkitElement = HTMLElement & { webkitRequestFullscreen?: () => void }

function fullscreenElement() {
  const d = document as WebkitDocument
  return d.fullscreenElement ?? d.webkitFullscreenElement ?? null
}

/** 网页全屏（阅读页）：不支持时按设备给出提示 */
export function useFullscreen() {
  const message = useMessage()
  const isFullscreen = ref(false)

  function toggleFullscreen() {
    const d = document as WebkitDocument
    if (fullscreenElement()) {
      if (d.exitFullscreen) d.exitFullscreen().catch(() => {})
      else d.webkitExitFullscreen?.()
      return
    }
    const el = document.documentElement as WebkitElement
    if (el.requestFullscreen) el.requestFullscreen().catch(showUnsupported)
    else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen()
    else showUnsupported()
  }

  /** iPhone 的浏览器（都使用 Safari 内核）不支持网页全屏，只能添加到主屏幕后打开 */
  function showUnsupported() {
    const standalone = matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone
    if (standalone) {
      message.info(t('当前已是全屏模式，系统不允许网页隐藏顶部状态栏'), { duration: 4000 })
    } else if (/iPhone|iPod/.test(navigator.userAgent)) {
      message.info(t('iPhone 的浏览器不支持网页全屏。可在 Safari 中点「分享」→「添加到主屏幕」，从主屏幕打开即可全屏阅读'), { duration: 6000 })
    } else {
      message.info(t('当前浏览器不支持全屏'), { duration: 3000 })
    }
  }

  function onChange() {
    isFullscreen.value = !!fullscreenElement()
  }

  onMounted(() => {
    document.addEventListener('fullscreenchange', onChange)
    document.addEventListener('webkitfullscreenchange', onChange)
  })
  onBeforeUnmount(() => {
    document.removeEventListener('fullscreenchange', onChange)
    document.removeEventListener('webkitfullscreenchange', onChange)
  })

  return { isFullscreen, toggleFullscreen }
}
