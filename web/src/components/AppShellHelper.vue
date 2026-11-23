<script setup lang="ts">
/**
 * 全局：PWA 新版本提示、联网状态、离线期间阅读进度的同步
 */
import { h, onBeforeUnmount, onMounted, watch } from 'vue'
import { NButton, useMessage, useNotification } from 'naive-ui'
import { useRegisterSW } from 'virtual:pwa-register/vue'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

const message = useMessage()
const notification = useNotification()
const auth = useAuthStore()
const offline = useOfflineStore()

/** 页面一直开着时（PWA 常驻）检查更新的间隔 */
const CHECK_INTERVAL_MS = 30 * 60_000

let registration: ServiceWorkerRegistration | undefined
let lastCheck = Date.now()
function checkUpdate() {
  if (!registration || !navigator.onLine) return
  lastCheck = Date.now()
  const r = registration
  r.update().then(() => checkWaiting(r)).catch(() => {})
}

/**
 * 新版本已下载好（在等待接管页面）时提示刷新。
 * 不只依赖 workbox-window 的事件：页面打开超过 1 分钟后发现的更新它当作“外部更新”，
 * 而且只处理一次，之后再有新版本就不提示了；安卓安装的应用常驻后台几天不重新加载，正好碰上。
 * 用户关掉提示后，下次检查（切回应用）时再提示
 */
function checkWaiting(r: ServiceWorkerRegistration) {
  if (r.waiting && navigator.serviceWorker.controller && !needRefresh.value) needRefresh.value = true
}

/** 让新版本接管页面后刷新：新版本接管（controllerchange）时重新加载 */
let reloading = false
function applyUpdate() {
  const waiting = registration?.waiting
  if (!waiting) {
    // 已经被别的窗口激活了，直接刷新就是新版本
    location.reload()
    return
  }
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloading) return
    reloading = true
    location.reload()
  })
  waiting.postMessage({ type: 'SKIP_WAITING' })
}

const { needRefresh } = useRegisterSW({
  immediate: true,
  onRegisteredSW(_url, r) {
    registration = r
    if (!r) return
    setInterval(checkUpdate, CHECK_INTERVAL_MS)
    // 发现新版本就先提示（新版本的文件下载完之前还不能刷新，下载完再换成“立即刷新”）；
    // 没有旧版本在控制页面时是第一次安装，不提示
    const track = () => {
      const sw = r.installing
      if (!sw || !navigator.serviceWorker.controller) return
      showPreparing()
      // 新版本下载完成（installed）后进入等待，提示刷新
      sw.addEventListener('statechange', () => {
        if (sw.state === 'installed') checkWaiting(r)
      })
    }
    r.addEventListener('updatefound', track)
    // 页面打开时浏览器可能已经在下载新版本（事件在注册完成前就触发了）
    track()
    // 上次打开时已经下载好、还没刷新的新版本
    checkWaiting(r)
  },
})

type NotificationHandle = ReturnType<typeof notification.info>
let preparing: NotificationHandle | null = null
function showPreparing() {
  if (preparing || needRefresh.value) return
  preparing = notification.info({
    title: t('发现新版本'),
    content: t('正在准备，完成后可以刷新使用新版本…'),
    onClose: () => {
      preparing = null
    },
  })
}

// 切回页面时检查一次（最多每分钟一次）：PWA 常驻后台时不用手动刷新也能发现新版本
function onVisible() {
  if (document.visibilityState === 'visible' && Date.now() - lastCheck > 60_000) checkUpdate()
}

watch(needRefresh, (v) => {
  if (!v) return
  // 新版本准备好了：换成可以刷新的提示，由用户决定何时刷新（避免阅读中被打断）
  preparing?.destroy()
  preparing = null
  const n = notification.info({
    title: t('新版本已准备好'),
    content: t('刷新后生效。'),
    action: () =>
      h(NButton, { size: 'small', type: 'primary', onClick: () => { n.destroy(); applyUpdate() } }, () => t('立即刷新')),
    onClose: () => { needRefresh.value = false },
  })
})

async function sync(showMessage: boolean) {
  if (!auth.loggedIn) return
  const n = await offline.syncProgress()
  if (showMessage && n) message.success(t('已同步 {0} 本书的离线阅读进度', [n]))
}

function onOnline() {
  offline.setOnline(true)
  message.success(t('网络已恢复'))
  sync(true)
  checkUpdate()
}

function onOffline() {
  offline.setOnline(false)
  message.warning(t('网络已断开，可以继续阅读已离线下载的书'), { duration: 5000 })
}

// 连着网但服务器不可达时 online 事件不会触发：离线期间定时探测服务器
let timer: ReturnType<typeof setInterval> | null = null
async function probe() {
  if (offline.online || !navigator.onLine) return
  try {
    await authApi.config()
    offline.setOnline(true)
    sync(true)
  } catch {
    /* 仍然不可达 */
  }
}

onMounted(() => {
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  document.addEventListener('visibilitychange', onVisible)
  // 安卓安装的应用从后台切回时不一定触发 visibilitychange，回到前台的其它事件也检查一次
  window.addEventListener('focus', onVisible)
  window.addEventListener('pageshow', onVisible)
  timer = setInterval(probe, 30_000)
  setTimeout(() => sync(true), 3000)
})

onBeforeUnmount(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('focus', onVisible)
  window.removeEventListener('pageshow', onVisible)
  if (timer) clearInterval(timer)
})
</script>

<template>
  <span style="display: none" />
</template>
