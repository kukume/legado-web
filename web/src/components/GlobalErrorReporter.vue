<script setup lang="ts">
import { getCurrentInstance, onUnmounted } from 'vue'
import { useMessage } from 'naive-ui'
import { ApiError, NetworkError } from '@/api/http'

/**
 * 兜底的错误提示：按钮、弹窗确认等处理函数里没有自己 catch 的接口错误，原来只出现在控制台（点了没反应），
 * 这里统一弹出错误信息。只处理服务器返回的接口错误（ApiError）；连不上服务器由离线模式处理（离线阅读时不打扰），
 * 401 由登录失效逻辑处理，取消的请求不提示。
 * 其他错误（代码问题）照常输出到控制台。
 */
const message = useMessage()
const app = getCurrentInstance()!.appContext.app

let last = { text: '', at: 0 }
function report(e: unknown): boolean {
  if (!(e instanceof ApiError) || e instanceof NetworkError || e.status === 401) return false
  const text = e.message || 'Error'
  const now = Date.now()
  // 同一个错误短时间内只提示一次（如几个请求同时失败）
  if (text !== last.text || now - last.at > 2000) message.error(text)
  last = { text, at: now }
  return true
}

const prevHandler = app.config.errorHandler
app.config.errorHandler = (err, instance, info) => {
  if (report(err)) return
  if (prevHandler) prevHandler(err, instance, info)
  else console.error(err)
}

function onRejection(ev: PromiseRejectionEvent) {
  if (report(ev.reason)) ev.preventDefault()
}
window.addEventListener('unhandledrejection', onRejection)

onUnmounted(() => {
  window.removeEventListener('unhandledrejection', onRejection)
  app.config.errorHandler = prevHandler
})
</script>

<template>
  <span v-if="false" />
</template>
