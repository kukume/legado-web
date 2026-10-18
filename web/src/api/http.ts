import { locale } from '@/i18n'
import { t } from '@/i18n'

const TOKEN_KEY = 'legado_token'

export function getToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setToken(token: string) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

/** 当前浏览器标签页的 id：书源脚本弹出的网页只推送给发起操作的标签页 */
const clientId = (() => {
  const rand = () => Math.random().toString(36).slice(2, 10)
  return `${Date.now().toString(36)}${rand()}${rand()}`
})()

export function getClientId(): string {
  return clientId
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

/** 连不上服务器（断网、服务器不可用） */
export class NetworkError extends ApiError {
  constructor() {
    super(0, t('网络不可用，无法连接服务器'))
    this.name = 'NetworkError'
  }
}

export function isNetworkError(e: unknown): boolean {
  return e instanceof NetworkError || (typeof navigator !== 'undefined' && navigator.onLine === false)
}

type Handler = () => void
let unauthorizedHandler: Handler | null = null
export function onUnauthorized(h: Handler) {
  unauthorizedHandler = h
}

async function parseError(res: Response): Promise<ApiError> {
  let msg = t('请求失败 ({0})', [res.status])
  try {
    const data = await res.json()
    if (data?.error) msg = data.error
  } catch {
    /* ignore */
  }
  if (res.status === 401) unauthorizedHandler?.()
  return new ApiError(res.status, msg)
}

export async function request<T = any>(
  method: string,
  url: string,
  body?: unknown,
  opts: { raw?: boolean; signal?: AbortSignal } = {},
): Promise<T> {
  const headers: Record<string, string> = { "X-Client-Id": clientId, 'Accept-Language': locale }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let payload: BodyInit | undefined
  if (body !== undefined) {
    if (body instanceof Blob) {
      payload = body
      headers['Content-Type'] = body.type || 'application/octet-stream'
    } else if (typeof body === 'string' && opts.raw) {
      payload = body
      headers['Content-Type'] = 'text/plain; charset=utf-8'
    } else {
      payload = JSON.stringify(body)
      headers['Content-Type'] = 'application/json'
    }
  }
  let res: Response
  try {
    res = await fetch(url, { method, headers, body: payload, signal: opts.signal })
  } catch (e: any) {
    if (e?.name === 'AbortError') throw e
    throw new NetworkError()
  }
  // 反向代理在后端不可用时返回的 502/503/504（非 JSON）也按网络不可用处理
  if ([502, 503, 504].includes(res.status) && !res.headers.get('content-type')?.includes('json')) {
    throw new NetworkError()
  }
  if (!res.ok) throw await parseError(res)
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const http = {
  get: <T = any>(url: string, signal?: AbortSignal) => request<T>('GET', url, undefined, { signal }),
  post: <T = any>(url: string, body?: unknown) => request<T>('POST', url, body ?? {}),
  postRaw: <T = any>(url: string, body: string) => request<T>('POST', url, body, { raw: true }),
  postBlob: <T = any>(url: string, body: Blob) => request<T>('POST', url, body),
  put: <T = any>(url: string, body?: unknown) => request<T>('PUT', url, body ?? {}),
  putRaw: <T = any>(url: string, body: string) => request<T>('PUT', url, body, { raw: true }),
  patch: <T = any>(url: string, body?: unknown) => request<T>('PATCH', url, body ?? {}),
  del: <T = any>(url: string) => request<T>('DELETE', url),
}

/**
 * 读取 NDJSON 流（搜索、书源调试）
 */
export async function streamNdjson(
  method: 'GET' | 'POST',
  url: string,
  body: unknown,
  onItem: (item: any) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(url, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal,
  })
  if (!res.ok) throw await parseError(res)
  const reader = res.body!.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim()
      buf = buf.slice(idx + 1)
      if (line) {
        try {
          onItem(JSON.parse(line))
        } catch {
          /* ignore bad line */
        }
      }
    }
  }
  if (buf.trim()) {
    try {
      onItem(JSON.parse(buf))
    } catch {
      /* ignore */
    }
  }
}

/** 通过后端代理加载图片（带书源请求头、cookie，支持封面解密） */
export function proxyImage(
  url: string | null | undefined,
  opts: { sourceId?: number | null; origin?: string; bookId?: number; cover?: boolean } = {},
): string {
  if (!url) return ''
  if (url.startsWith('data:image')) return url
  // 本站接口返回的图片（本地书籍的插图、封面）：直接请求，带上登录凭据
  if (url.startsWith('/api/')) return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(getToken())
  const p = new URLSearchParams({ url, token: getToken() })
  if (opts.sourceId) p.set('sourceId', String(opts.sourceId))
  if (opts.origin) p.set('origin', opts.origin)
  if (opts.bookId) p.set('bookId', String(opts.bookId))
  if (opts.cover) p.set('cover', 'true')
  return `/api/proxy/image?${p.toString()}`
}
