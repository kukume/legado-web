import type { ComputedRef, Ref } from 'vue'
import type { PageAnim } from '@/stores/reader'

/**
 * 翻页模式的翻页动画：拍下旧页的快照，页面切换到新页后在快照与新页之间做动画
 * （平移、覆盖、仿真、淡入淡出）。快照是动态创建的元素，样式由 ReaderView 中的 .page-ghost / .page-sheet / .page-flap 提供
 */
export function usePageAnimation(opts: {
  pager: Ref<HTMLElement | null>
  pageClip: Ref<HTMLElement | null>
  isVertical: ComputedRef<boolean>
  pageMetrics: { width: number; gap: number; height: number }
  pageLoading: Ref<boolean>
  pageAnim: ComputedRef<PageAnim>
}) {
  const { pager, pageClip, isVertical, pageMetrics, pageLoading, pageAnim } = opts

  /** 复制当前页的正文区域作为“旧页”（平移动画用，背景透明，只移动文字），新章节加载期间也保持显示 */
  function snapshotPage(): HTMLElement | null {
    const clip = pageClip.value
    const p = pager.value
    if (!clip || !p || pageLoading.value) return null
    const cr = clip.getBoundingClientRect()
    const pr = p.getBoundingClientRect()
    const ghost = clip.cloneNode(true) as HTMLElement
    ghost.classList.add("page-ghost")
    Object.assign(ghost.style, {
      position: "absolute",
      left: `${cr.left - pr.left}px`,
      top: `${cr.top - pr.top}px`,
      width: `${cr.width}px`,
      height: `${cr.height}px`,
    })
    p.appendChild(ghost)
    return ghost
  }

  /** 动态创建的元素没有组件的 scoped 样式属性，从翻页容器复制过来，样式才能生效 */
  function scopedDiv(className: string): HTMLElement {
    const el = document.createElement("div")
    el.className = className
    const attr = pager.value?.getAttributeNames().find((n) => n.startsWith("data-v-"))
    if (attr) el.setAttribute(attr, "")
    return el
  }

  /** 整页快照（含页边距和纸张背景，不透明）：覆盖、仿真、淡入淡出动画用 */
  function snapshotFull(): HTMLElement | null {
    const clip = pageClip.value
    const p = pager.value
    if (!clip || !p || pageLoading.value) return null
    const cr = clip.getBoundingClientRect()
    const pr = p.getBoundingClientRect()
    const sheet = scopedDiv("page-sheet")
    const inner = clip.cloneNode(true) as HTMLElement
    Object.assign(inner.style, {
      position: "absolute",
      left: `${cr.left - pr.left}px`,
      top: `${cr.top - pr.top}px`,
      width: `${cr.width}px`,
      height: `${cr.height}px`,
    })
    sheet.appendChild(inner)
    p.appendChild(sheet)
    return sheet
  }


  /** 正在进行的翻页动画的结束函数：连续翻页时先结束上一次动画，避免快照叠在一起 */
  const runningPageAnims = new Set<() => void>()
  function finishPageAnims() {
    for (const f of Array.from(runningPageAnims)) f()
  }

  /**
   * 旧页（快照）与新页之间的翻页动画，调用时页面已经切换到新页：
   * - slide 旧页滑出、新页滑入
   * - cover 向后翻时新页从侧面整页盖上来，向前翻时旧页整页滑开露出新页
   * - curl 仿真：向后翻时旧页从右下角卷起翻走，向前翻时上一页从左侧翻回来盖上
   * - fade 旧页淡出
   */
  function animatePages(old: HTMLElement, anim: "next" | "prev") {
    const p = pager.value
    if (!p) return old.remove()
    const kind = pageAnim.value
    const els: HTMLElement[] = [old]
    const anims: Animation[] = []
    let stopCurl: (() => void) | null = null
    let finished = false
    const finish = () => {
      if (finished) return
      finished = true
      runningPageAnims.delete(finish)
      stopCurl?.()
      for (const a of anims) a.cancel()
      for (const el of els) el.remove()
    }
    runningPageAnims.add(finish)

    const vertical = isVertical.value
    const axis = vertical ? "translateY" : "translateX"
    const move = (from: number, to: number) => [{ transform: `${axis}(${from}px)` }, { transform: `${axis}(${to}px)` }]
    // fill: forwards 让动画停在结束状态，直到快照被移除；否则动画一结束快照会跳回原位，旧页面闪一下
    const opts: KeyframeAnimationOptions = { duration: 250, easing: "ease", fill: "forwards" }
    let duration = 250

    if (kind === "slide") {
      const clip = pageClip.value
      const dist = vertical ? pageMetrics.height : pageMetrics.width + pageMetrics.gap
      const sign = anim === "next" ? 1 : -1
      anims.push(old.animate(move(0, -sign * dist), opts))
      if (clip) anims.push(clip.animate(move(sign * dist, 0), opts))
    } else if (kind === "fade") {
      old.classList.add("top")
      duration = 220
      anims.push(old.animate([{ opacity: 1 }, { opacity: 0 }], { duration, easing: "ease", fill: "forwards" }))
    } else if (kind === "cover") {
      const dist = vertical ? p.clientHeight : p.clientWidth
      if (anim === "next") {
        // 旧页垫在下面，新页（快照）带阴影整页盖上来
        const sheet = snapshotFull()
        old.classList.add("under")
        if (sheet) {
          els.push(sheet)
          sheet.classList.add("top", "shadow")
          anims.push(sheet.animate(move(dist, 0), opts))
        }
      } else {
        // 旧页在上面整页滑开，露出下面的新页
        old.classList.add("top", "shadow")
        anims.push(old.animate(move(0, dist), opts))
      }
    } else if (kind === "curl") {
      duration = 450
      if (anim === "next") {
        old.classList.add("top")
        stopCurl = curlPage(old, 0, 1, duration, els)
      } else {
        // 旧页垫在下面，上一页（快照）从翻开的状态翻回来
        const sheet = snapshotFull()
        old.classList.add("under")
        if (sheet) {
          els.push(sheet)
          sheet.classList.add("top")
          stopCurl = curlPage(sheet, 1, 0, duration, els)
        }
      }
    }
    // 动画结束立即移除快照；页面在后台时动画会暂停，超时也直接结束，保证快照一定被移除
    if (anims.length) Promise.all(anims.map((a) => a.finished)).then(finish, finish)
    setTimeout(finish, duration + 100)
  }

  /**
   * 仿真翻页：把 sheet 当作一张纸，右下角沿着底边（上下翻页时沿右边）被拉向另一侧。
   * 折线是“原角点”与“当前角点”连线的垂直平分线：折线一侧的纸仍在原处，另一侧按折线镜像翻到背面。
   * progress 从 from 变化到 to（0 为平放，1 为完全翻走）
   */
  function curlPage(sheet: HTMLElement, from: number, to: number, duration: number, els: HTMLElement[]): () => void {
    const p = pager.value!
    const W = p.clientWidth
    const H = p.clientHeight
    const vertical = isVertical.value
    const wrap = scopedDiv("page-flap-wrap")
    const flap = scopedDiv("page-flap")
    flap.style.width = `${W}px`
    flap.style.height = `${H}px`
    // 纸张背面隐约透出反向的文字
    const back = sheet.firstElementChild?.cloneNode(true) as HTMLElement | undefined
    if (back) {
      back.style.opacity = "0.12"
      flap.appendChild(back)
    }
    wrap.appendChild(flap)
    p.appendChild(wrap)
    els.push(wrap)

    type Pt = [number, number]
    const rect: Pt[] = [[0, 0], [W, 0], [W, H], [0, H]]
    // 保留多边形在直线 (X - m)·n 的哪一侧（Sutherland–Hodgman 裁剪）
    const clipHalf = (poly: Pt[], m: Pt, n: Pt, keepPositive: boolean): Pt[] => {
      const side = (q: Pt) => ((q[0] - m[0]) * n[0] + (q[1] - m[1]) * n[1]) * (keepPositive ? 1 : -1)
      const out: Pt[] = []
      for (let i = 0; i < poly.length; i++) {
        const a = poly[i]
        const b = poly[(i + 1) % poly.length]
        const da = side(a)
        const db = side(b)
        if (da >= 0) out.push(a)
        if (da * db < 0) {
          const t = da / (da - db)
          out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t])
        }
      }
      return out
    }
    const polygon = (pts: Pt[]) =>
      pts.length < 3 ? "polygon(0 0, 0 0, 0 0)" : `polygon(${pts.map(([x, y]) => `${x.toFixed(1)}px ${y.toFixed(1)}px`).join(", ")})`

    const corner: Pt = [W, H]
    const lift = (vertical ? W : H) * 0.12
    const render = (e: number) => {
      const s = Math.sin(Math.PI * e)
      const cur: Pt = vertical ? [W - lift * s, H - 2 * H * e] : [W - 2 * W * e, H - lift * s]
      const dx = corner[0] - cur[0]
      const dy = corner[1] - cur[1]
      const len = Math.hypot(dx, dy)
      if (len < 0.5) {
        sheet.style.clipPath = ""
        flap.style.display = "none"
        return
      }
      flap.style.display = ""
      const n: Pt = [dx / len, dy / len]
      const m: Pt = [(corner[0] + cur[0]) / 2, (corner[1] + cur[1]) / 2]
      // 纸张留在原处的部分（不含角点的一侧）
      sheet.style.clipPath = polygon(clipHalf(rect, m, n, false))
      // 翻起的部分：在纸张坐标中取含角点的一侧，再按折线镜像
      flap.style.clipPath = polygon(clipHalf(rect, m, n, true))
      const u: Pt = [-n[1], n[0]]
      const a = u[0] * u[0] - u[1] * u[1]
      const b = 2 * u[0] * u[1]
      const d = -a
      const tx = m[0] - (a * m[0] + b * m[1])
      const ty = m[1] - (b * m[0] + d * m[1])
      flap.style.transform = `matrix(${a}, ${b}, ${b}, ${d}, ${tx}, ${ty})`
      // 背面的光影：靠近折线处亮、往角点方向渐暗
      const angle = (Math.atan2(n[0], -n[1]) * 180) / Math.PI
      flap.style.backgroundImage = `linear-gradient(${angle}deg, rgba(255,255,255,0.22), rgba(0,0,0,0.04) 45%, rgba(0,0,0,0.14))`
    }

    const ease = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
    const t0 = performance.now()
    let raf = 0
    const step = (now: number) => {
      const t = Math.min(1, (now - t0) / duration)
      render(from + (to - from) * ease(t))
      if (t < 1) raf = requestAnimationFrame(step)
    }
    render(from)
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }

  return { snapshotPage, snapshotFull, animatePages, finishPageAnims }
}
