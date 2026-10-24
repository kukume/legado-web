/**
 * 顶部状态栏的颜色跟随页面实际的背景色：普通页面用界面主题的背景色（浅色 / 深色），阅读页用阅读主题的背景色。
 * - 浏览器（Android 等）：meta theme-color
 * - iOS 添加到主屏幕后打开：不看 theme-color，状态栏取根元素（html / body）的背景色，
 *   所以阅读页同时把 html / body 的背景改成阅读背景，离开时恢复
 */
let appColor = ''
let pageColor: string | null = null

function apply() {
  const root = document.documentElement
  root.style.backgroundColor = pageColor ?? ''
  document.body.style.backgroundColor = pageColor ?? ''

  const color = pageColor || appColor
  if (!color) return
  let meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (!meta) {
    meta = document.createElement('meta')
    meta.name = 'theme-color'
    document.head.appendChild(meta)
  }
  if (meta.content !== color) meta.content = color
}

/** 界面主题切换后调用：读取当前的 --app-bg */
export function syncAppThemeColor() {
  appColor = getComputedStyle(document.documentElement).getPropertyValue('--app-bg').trim()
  apply()
}

/** 页面（如阅读页）使用自己的背景色；传 null 恢复为界面主题的颜色 */
export function setPageThemeColor(color: string | null) {
  pageColor = color
  apply()
}
