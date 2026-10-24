import type { RouteLocationRaw, Router } from 'vue-router'

/**
 * 返回上一页；没有上一页时（刷新后、从书签或主屏幕直接打开、在新标签页打开）router.back() 什么也不做，
 * 这时改为跳到 fallback（replace，不在历史里多留一条）
 */
export function goBack(router: Router, fallback: RouteLocationRaw) {
  if (window.history.state?.back) router.back()
  else router.replace(fallback)
}
