import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { zlibApi } from '@/api'
import type { ZlibJob } from '@/api/types'

const ACTIVE: ZlibJob['status'][] = ['queued', 'downloading', 'importing']

/**
 * Z-Library 下载任务（全局）：不在 Z-Library 页面时也继续查询下载进度，
 * 下载完成 / 失败时通过 [onFinished] 通知（由 AppLayout 弹出提示），导入成功后 [importedTick] 加一（书架据此刷新）
 */
export const useZlibStore = defineStore('zlib', () => {
  const jobs = ref<ZlibJob[]>([])
  const activeCount = computed(() => jobs.value.filter((j) => ACTIVE.includes(j.status)).length)
  /** 每导入一本书加一 */
  const importedTick = ref(0)

  const listeners = new Set<(job: ZlibJob) => void>()
  /** 任务从进行中变为完成或失败时调用 */
  function onFinished(fn: (job: ZlibJob) => void) {
    listeners.add(fn)
    return () => listeners.delete(fn)
  }

  let timer: number | null = null

  /** 拉取一次任务列表；有进行中的任务时自动继续轮询 */
  async function refresh() {
    const before = new Map(jobs.value.map((j) => [j.id, j.status]))
    try {
      jobs.value = await zlibApi.jobs()
    } catch {
      /* 忽略一次查询失败 */
      return
    }
    for (const j of jobs.value) {
      const prev = before.get(j.id)
      if (prev && ACTIVE.includes(prev) && !ACTIVE.includes(j.status)) {
        if (j.status === 'done') importedTick.value++
        listeners.forEach((fn) => fn(j))
      }
    }
    if (activeCount.value > 0) start()
    else stop()
  }

  function start() {
    if (timer == null) timer = window.setInterval(refresh, 1500)
  }
  function stop() {
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 新建任务后加入列表并开始轮询 */
  function add(job: ZlibJob) {
    jobs.value = [job, ...jobs.value.filter((j) => j.id !== job.id)]
    start()
  }

  return { jobs, activeCount, importedTick, onFinished, refresh, add }
})
