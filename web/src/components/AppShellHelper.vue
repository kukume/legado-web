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
  registration.update().catch(() => {})
}

const { needRefresh, updateServiceWorker } = useRegisterSW({
  immediate: true,
  onRegisteredSW(_url, r) {
    registration = r
    if (!r) return
    setInterval(checkUpdate, CHECK_INTERVAL_MS)
    // 发现新版本就先提示（新版本的文件下载完之前还不能刷新，下载完再换成“立即刷新”）；
    // 没有旧版本在控制页面时是第一次安装，不提示
    r.addEventListener('updatefound', () => {
      if (navigator.serviceWorker.controller) showPreparing()
    })
    // 页面打开时浏览器可能已经在下载新版本（事件在注册完成前就触发了）
    if (r.installing && navigator.serviceWorker.controller) showPreparing()
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
      h(NButton, { size: 'small', type: 'primary', onClick: () => { n.destroy(); updateServiceWorker(true) } }, () => t('立即刷新')),
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
  timer = setInterval(probe, 30_000)
  setTimeout(() => sync(true), 3000)
})

onBeforeUnmount(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  document.removeEventListener('visibilitychange', onVisible)
  if (timer) clearInterval(timer)
})
</script>

<template>
  <span style="display: none" />
</template>
