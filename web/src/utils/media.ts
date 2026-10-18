import { onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '@/i18n'

export function usePreferredDark() {
  const mq = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null
  const dark = ref(mq?.matches ?? false)
  mq?.addEventListener('change', (e) => (dark.value = e.matches))
  return dark
}

/** 是否为窄屏（手机） */
export function useIsMobile(breakpoint = 768) {
  const mobile = ref(typeof window !== 'undefined' ? window.innerWidth < breakpoint : false)
  const update = () => (mobile.value = window.innerWidth < breakpoint)
  onMounted(() => window.addEventListener('resize', update))
  onBeforeUnmount(() => window.removeEventListener('resize', update))
  return mobile
}

export function formatTime(ts?: number | null): string {
  if (!ts) return '-'
  const d = new Date(ts)
  const diff = Date.now() - ts
  if (diff < 60_000) return t('刚刚')
  if (diff < 3600_000) return t('{0} 分钟前', [Math.floor(diff / 60_000)])
  if (diff < 86400_000) return t('{0} 小时前', [Math.floor(diff / 3600_000)])
  if (diff < 30 * 86400_000) return t('{0} 天前', [Math.floor(diff / 86400_000)])
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function formatDate(ts?: number | null): string {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  }
}

export function downloadText(filename: string, text: string, type = 'application/json') {
  const blob = new Blob([text], { type })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
