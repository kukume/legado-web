import { onActivated } from 'vue'

/**
 * 页面被缓存（keep-alive）后再次显示时执行 [fn]。第一次显示不执行，那时组件刚挂载，已经在 onMounted 里加载过了
 */
export function useReactivated(fn: () => void) {
  let first = true
  onActivated(() => {
    if (first) {
      first = false
      return
    }
    fn()
  })
}
