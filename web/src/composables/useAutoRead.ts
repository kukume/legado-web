import { onBeforeUnmount, ref } from 'vue'

/**
 * 自动阅读：上下滚动模式按速度匀速滚动；翻页模式每隔一段时间翻一页。
 * blocked() 为 true 时（菜单、抽屉、弹窗打开，正在选文字、朗读，页面在后台）暂停计时，恢复后接着走
 */
export function useAutoRead(opts: {
  isPaged: () => boolean
  scroller: () => HTMLElement | null
  /** 滚动速度（像素 / 秒） */
  speed: () => number
  /** 翻页间隔（秒） */
  seconds: () => number
  nextPage: () => unknown
  /** 已经到了全书末尾（滚动到底且没有下一章 / 最后一章最后一页） */
  atEnd: () => boolean
  blocked: () => boolean
  onEnd: () => void
}) {
  const running = ref(false)
  /** 翻页模式：当前这一页已经停留的时间（毫秒），用于显示进度 */
  const elapsed = ref(0)
  let raf = 0
  let last = 0
  /** 滚动的小数部分（每帧滚动不足 1 像素时累积） */
  let acc = 0

  function frame(now: number) {
    if (!running.value) return
    const dt = last ? Math.min(100, now - last) : 0
    last = now
    if (!opts.blocked() && dt > 0) {
      if (opts.isPaged()) {
        elapsed.value += dt
        if (elapsed.value >= opts.seconds() * 1000) {
          elapsed.value = 0
          if (opts.atEnd()) return finish()
          opts.nextPage()
        }
      } else {
        const s = opts.scroller()
        if (s) {
          acc += (opts.speed() * dt) / 1000
          const step = Math.floor(acc)
          if (step > 0) {
            acc -= step
            s.scrollTop += step
          }
          if (opts.atEnd()) return finish()
        }
      }
    }
    raf = requestAnimationFrame(frame)
  }

  function finish() {
    stop()
    opts.onEnd()
  }

  function start() {
    if (running.value) return
    running.value = true
    elapsed.value = 0
    acc = 0
    last = 0
    raf = requestAnimationFrame(frame)
  }

  function stop() {
    running.value = false
    cancelAnimationFrame(raf)
    elapsed.value = 0
  }

  /** 翻页模式：手动翻页后重新计时 */
  function resetTimer() {
    elapsed.value = 0
  }

  onBeforeUnmount(stop)

  return { running, elapsed, start, stop, resetTimer }
}
