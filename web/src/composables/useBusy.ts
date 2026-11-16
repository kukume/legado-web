import { reactive } from 'vue'

/**
 * 防重复提交：同一个操作进行中再次点击直接忽略，按钮用 busy.is(key) 显示加载状态。
 *
 *   const busy = useBusy()
 *   <n-button :loading="busy.is('save')" @click="busy.run('save', save)">
 */
export function useBusy() {
  const running = reactive(new Set<string>())
  async function run<T>(key: string, fn: () => T | Promise<T>): Promise<T | undefined> {
    if (running.has(key)) return undefined
    running.add(key)
    try {
      return await fn()
    } finally {
      running.delete(key)
    }
  }
  return { run, is: (key: string) => running.has(key) }
}
