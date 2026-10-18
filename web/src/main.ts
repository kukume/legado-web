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
}
start()
