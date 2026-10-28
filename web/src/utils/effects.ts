import { h, ref } from 'vue'
import { useMessage, type MessageApi } from 'naive-ui'
import type { ActionResult } from '@/api'
import { copyText } from './media'
import { t } from '@/i18n'

/**
 * 显示书源脚本的提示（java.toast / longToast）：保留其中的换行；
 * 书源按 App 的 Android 提示写，常在开头、结尾加空行留白，这里去掉首尾空行
 */
export function showSourceToast(message: MessageApi, text: string) {
  const content = text.trim()
  if (!content) return
  message.info(() => h('div', { style: 'white-space: pre-line' }, content), { duration: 5000 })
}

/**
 * 处理书源脚本产生的副作用：提示、打开网页、复制文本
 */
export function useScriptEffects() {
  const message = useMessage()
  /** 书源请求打开的网页（弹窗被浏览器拦截时让用户手动点击） */
  const openLinks = ref<string[]>([])

  async function apply(r: Partial<ActionResult> | null | undefined) {
    if (!r) return
    r.messages?.forEach((m) => m && showSourceToast(message, m))
    if (r.openUrls?.length) {
      openLinks.value = r.openUrls
      r.openUrls.forEach((u) => window.open(u, '_blank', 'noopener'))
    }
    if (r.clipboard) {
      ;(await copyText(r.clipboard)) ? message.success(t('已复制到剪贴板')) : message.warning(t('请手动复制：{0}', [r.clipboard]))
    }
  }

  return { apply, openLinks }
}
