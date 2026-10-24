import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { adminApi, type UpdateStatus } from '@/api'

/**
 * 检查更新（只有管理员）：打开网页时检查一次，结果给侧边栏的提示点、系统管理页共用。
 * 服务端 30 分钟内只真正查询一次镜像仓库
 */
export const useUpdateStore = defineStore('update', () => {
  const status = ref<UpdateStatus | null>(null)
  let started = false

  /** 打开网页时调用；同一个页面只检查一次 */
  async function autoCheck() {
    if (started) return
    started = true
    try {
      status.value = await adminApi.update(true)
    } catch {
      /* 检查失败不影响使用 */
    }
  }

  /** 读取状态，不触发检查（系统管理页） */
  async function load() {
    status.value = await adminApi.update()
  }

  /** 立即检查（“检查更新”按钮） */
  async function check() {
    status.value = await adminApi.checkUpdate()
    return status.value
  }

  const hasUpdate = computed(() => !!status.value?.hasUpdate)

  return { status, hasUpdate, autoCheck, load, check }
})

/**
 * 版本号：构建时间（秒）按北京时间格式化，如 20261024-1530，与镜像标签相同
 * （GitHub Actions 中 TZ=Asia/Shanghai date +%Y%m%d-%H%M）
 */
export function versionName(sec: number | null | undefined): string {
  if (!sec) return ''
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(sec * 1000)).map((p) => [p.type, p.value]),
  )
  return `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}`
}
