import { onBeforeUnmount, onMounted, watch } from 'vue'

type WakeLockHandle = { release: () => Promise<void>; addEventListener: (t: 'release', f: () => void) => void }

/** 浏览器是否支持屏幕常亮（需要 HTTPS） */
export const wakeLockSupported = typeof navigator !== 'undefined' && 'wakeLock' in navigator

/**
 * 屏幕常亮（Screen Wake Lock）：页面打开期间不让手机自动熄屏。
 * 页面切到后台时系统会自动释放，回到前台再申请；enabled 返回 false 时释放
 */
export function useWakeLock(enabled: () => boolean) {
  let wakeLock: WakeLockHandle | null = null

  async function acquire() {
    if (!wakeLockSupported || wakeLock || !enabled() || document.visibilityState !== 'visible') return
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

  function release() {
    const lock = wakeLock
    wakeLock = null
    lock?.release().catch(() => {})
  }

  function onVisibility() {
    if (document.visibilityState === 'visible') acquire()
  }
  // 部分浏览器要求有过用户操作才能申请：第一次点击 / 触摸时再试一次
  function onFirstInteraction() {
    acquire()
  }

  watch(enabled, (v) => (v ? acquire() : release()))

  onMounted(() => {
    acquire()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pointerdown', onFirstInteraction, { once: true })
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('pointerdown', onFirstInteraction)
    release()
  })
}
