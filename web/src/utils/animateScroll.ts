/**
 * 滚动动画（先快后慢）。不用 scrollTo / scrollBy 的 smooth：系统关闭了动画效果
 * （prefers-reduced-motion，如 Windows 的“动画效果”）时浏览器会直接跳过去。
 *
 * 用定时器而不是 requestAnimationFrame：标签页在后台时 rAF 不执行，动画会停在原地；
 * 定时器在后台会被放慢，按经过的时间计算位置，最多慢一点到达。
 */

interface Running {
  target: number
  timer: number
  /** 动画的用途（如 follow：跟随其他端），只取消某一种动画时用 */
  tag?: string
}

const running = new WeakMap<HTMLElement, Running>()

/** 滚动到 top */
export function animateScrollTo(el: HTMLElement, top: number, duration = 450, tag?: string) {
  cancelScrollAnimation(el)
  const from = el.scrollTop
  const to = Math.max(0, Math.min(top, el.scrollHeight - el.clientHeight))
  if (Math.abs(to - from) < 2) return
  const start = performance.now()
  const state: Running = { target: to, timer: 0, tag }
  const step = () => {
    const t = Math.min(1, (performance.now() - start) / duration)
    el.scrollTop = from + (to - from) * (1 - Math.pow(1 - t, 3))
    if (t < 1) state.timer = window.setTimeout(step, 16)
    else running.delete(el)
  }
  running.set(el, state)
  step()
}

/** 滚动 delta；上一次动画还没结束时接着它的终点算（连续点击翻屏不会少翻） */
export function animateScrollBy(el: HTMLElement, delta: number, duration = 300) {
  const base = running.get(el)?.target ?? el.scrollTop
  animateScrollTo(el, base + delta, duration)
}

/** 停下 el 正在进行的滚动动画（用户自己滚动时）；指定 tag 时只停下这种动画 */
export function cancelScrollAnimation(el: HTMLElement | null | undefined, tag?: string) {
  if (!el) return
  const r = running.get(el)
  if (!r || (tag && r.tag !== tag)) return
  clearTimeout(r.timer)
  running.delete(el)
}
