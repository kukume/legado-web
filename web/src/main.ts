import { initI18n, t } from './i18n'
import './styles/base.css'

// 先加载界面语言的翻译，再加载应用代码：各模块顶层用 t() 生成的文字（路由标题、菜单等）才是翻译后的
async function start() {
  await initI18n().catch(() => {})
  const [{ createApp }, { createPinia }, { default: App }, { default: router }] = await Promise.all([
    import('vue'),
    import('pinia'),
    import('./App.vue'),
    import('./router'),
  ])
  const app = createApp(App)
  // 模板里可以直接调用翻译函数 t
  app.config.globalProperties.t = t
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
  // 第一个页面（含路由守卫、页面组件）准备好后再收起启动画面，不露出中间的空白
  await router.isReady().catch(() => {})
  hideBoot()
}

/** 启动画面（index.html 的 #boot）显示过（淡入开始后）至少停留这么久再淡出，避免一闪而过 */
const BOOT_MIN_VISIBLE_MS = 500

function hideBoot() {
  const el = document.getElementById('boot')
  if (!el) return
  const shownAt = (window as { __bootShownAt?: number }).__bootShownAt
  const wait = shownAt === undefined ? 0 : Math.max(0, BOOT_MIN_VISIBLE_MS - (performance.now() - shownAt))
  setTimeout(() => {
    // 加载快时启动画面还没淡入：这时只是一层底色，淡出等于让页面渐渐显现
    el.classList.add('boot-out')
    setTimeout(() => el.remove(), 250)
  }, wait)
}

start()
