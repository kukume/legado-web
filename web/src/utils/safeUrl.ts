/**
 * 书源返回的链接绑到 :href 时，过滤掉 javascript:、data: 等危险协议，只放行 http/https。
 * 不安全时返回 undefined——href 属性不渲染，点击无效果。
 */
export function safeHref(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  return /^https?:\/\//i.test(url.trim()) ? url : undefined
}
