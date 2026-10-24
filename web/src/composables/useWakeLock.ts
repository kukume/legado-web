import { onBeforeUnmount, onMounted, watch } from 'vue'

type WakeLockHandle = { release: () => Promise<void>; addEventListener: (t: 'release', f: () => void) => void }

const hasWakeLockApi = typeof navigator !== 'undefined' && 'wakeLock' in navigator

/** iPhone / iPad（iPadOS 的 Safari 标识为 Mac，用触摸点数区分） */
const isIos =
  typeof navigator !== 'undefined' &&
  (/iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

/**
 * iOS 用循环播放一个无声的小视频保持屏幕常亮（播放视频时系统不会自动锁屏）：
 * - iOS 16.4 以前没有 Screen Wake Lock
 * - 添加到主屏幕后打开时，iOS 18.4 以前 Screen Wake Lock 申请成功但不起作用（WebKit bug 254545）
 */
const useVideo = isIos

/** 浏览器是否支持屏幕常亮 */
export const wakeLockSupported = hasWakeLockApi || useVideo

/**
 * 屏幕常亮：页面打开期间不让手机自动熄屏。
 * 页面切到后台时系统会自动释放，回到前台再申请；enabled 返回 false 时释放
 */
export function useWakeLock(enabled: () => boolean) {
  let wakeLock: WakeLockHandle | null = null
  let video: HTMLVideoElement | null = null

  async function acquire() {
    if (!enabled() || document.visibilityState !== 'visible') return
    if (useVideo) playVideo()
    if (!hasWakeLockApi || wakeLock) return
    try {
      const lock = await (navigator as unknown as { wakeLock: { request: (t: 'screen') => Promise<WakeLockHandle> } }).wakeLock.request('screen')
      wakeLock = lock
      lock.addEventListener('release', () => {
        if (wakeLock === lock) wakeLock = null
      })
    } catch {
      /* 省电模式、未在 HTTPS 下等情况会被拒绝，忽略 */
    }
  }

  function playVideo() {
    if (!video) {
      video = document.createElement('video')
      video.src = '/wakelock.mp4'
      video.muted = true
      video.loop = true
      video.playsInline = true
      video.setAttribute('playsinline', '')
      video.setAttribute('aria-hidden', 'true')
      video.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;pointer-events:none'
      document.body.appendChild(video)
    }
    // 没有用户操作时可能被拒绝，点击 / 触摸时会再试
    if (video.paused) video.play().catch(() => {})
  }

  function release() {
    const lock = wakeLock
    wakeLock = null
    lock?.release().catch(() => {})
    video?.pause()
  }

  function onVisibility() {
    if (document.visibilityState === 'visible') acquire()
  }
  // 浏览器要求有过用户操作才能申请或播放；系统也可能中途释放：每次点击 / 触摸时检查一次
  function onInteraction() {
    if (enabled() && (!wakeLock || video?.paused)) acquire()
  }

  watch(enabled, (v) => (v ? acquire() : release()))

  onMounted(() => {
    acquire()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pointerdown', onInteraction, true)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('pointerdown', onInteraction, true)
    release()
    video?.remove()
    video = null
  })
}
