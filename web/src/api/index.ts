import { ApiError, getToken, http, NetworkError, streamNdjson } from './http'
import { locale } from '@/i18n'
import type {
  Book, BookGroup, Bookmark, BookSearchResult, ReadingSummary, LocalBookInfo, TtsEngine, TtsEngineConfig, Chapter, ChapterContent, ExploreKind, ImportResult, Invite, LoginConfig,
  LoginV2Render, Me, PublicConfig, ReplaceRule, SearchBook, SourceSummary, Subscription, User,
  DictRule, TxtTocRule,
} from './types'
import { t } from '@/i18n'

export interface AuthResult {
  token: string
  user: User
}

export const authApi = {
  config: () => http.get<PublicConfig>('/api/auth/config'),
  login: (username: string, password: string, captchaToken?: string) =>
    http.post<AuthResult>('/api/auth/login', { username, password, captchaToken }),
  register: (username: string, password: string, inviteCode?: string, captchaToken?: string) =>
    http.post<AuthResult>('/api/auth/register', { username, password, inviteCode, captchaToken }),
  me: () => http.get<Me>('/api/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<AuthResult>('/api/auth/password', { oldPassword, newPassword }),
}

export const inviteApi = {
  list: () => http.get<Invite[]>('/api/invites'),
  create: (data: { count: number; maxUses: number; expireDays?: number | null; note?: string }) =>
    http.post<Invite[]>('/api/invites', data),
  setDisabled: (id: number, disabled: boolean) => http.patch(`/api/invites/${id}`, { disabled }),
  remove: (id: number) => http.del(`/api/invites/${id}`),
}

/** 检查更新：时间都是镜像的构建时间（秒），本地开发运行时 buildTime 为 null */
export interface UpdateStatus {
  buildTime: number | null
  image: string
  latestTime: number | null
  checkedAt: number | null
  error: string | null
  hasUpdate: boolean
  enabled: boolean
}

export const adminApi = {
  users: () => http.get<User[]>('/api/admin/users'),
  createUser: (username: string, password: string) =>
    http.post<User>('/api/admin/users', { username, password }),
  updateUser: (id: number, data: { enabled?: boolean; password?: string; canAddSource?: boolean }) =>
    http.patch<User>(`/api/admin/users/${id}`, data),
  deleteUser: (id: number) => http.del(`/api/admin/users/${id}`),
  settings: () => http.get<Record<string, string>>('/api/admin/settings'),
  saveSettings: (s: Record<string, string>) => http.put<Record<string, string>>('/api/admin/settings', s),
  /** 测试浏览器调试地址，返回浏览器版本 */
  testBrowser: (cdp: string) => http.post<{ browser: string; userAgent: string }>('/api/admin/browser/test', { cdp }),
  /** 当前版本（构建时间）与最近一次检查更新的结果 */
  /** auto 为 true 时（管理员打开网页）服务端按需检查一次 */
  update: (auto = false) => http.get<UpdateStatus>(`/api/admin/update${auto ? '?auto=true' : ''}`),
  checkUpdate: () => http.post<UpdateStatus>('/api/admin/update/check'),
}

export interface SourceDetail {
  id: number
  isPublic: boolean
  canEdit: boolean
  source: any
}

/** 书源校验结果 */
export interface SourceCheckResult {
  id: number
  name: string
  ok: boolean
  /** 失败时加上的分组：搜索失效、详情失效、目录失效、正文失效、校验超时 */
  group: string | null
  error: string | null
  time: number
  /** 分组是否已写回书源（公共书源普通用户不能修改） */
  saved: boolean
}

export type SourceCheckEvent =
  | { type: 'result'; done: number; total: number; result: SourceCheckResult }
  | { type: 'end' }

export interface ImportPreviewItem {
  name: string
  url: string
  group?: string | null
  status: 'new' | 'update' | 'exists'
  json: string
}

export const sourceApi = {
  /** 校验书源：搜索 → 详情 → 目录 → 正文，每校验完一个回调一次 */
  check: (ids: number[], keyword: string, onEvent: (e: SourceCheckEvent) => void, signal?: AbortSignal) =>
    streamNdjson('POST', '/api/sources/check', { ids, keyword: keyword || null }, onEvent, signal),
  list: () => http.get<SourceSummary[]>('/api/sources'),
  groups: () => http.get<string[]>('/api/sources/groups'),
  get: (id: number) => http.get<SourceDetail>(`/api/sources/${id}`),
  importText: (text: string, isPublic = false) =>
    http.post<ImportResult>('/api/sources/import', { text, isPublic }),
  importUrl: (url: string, isPublic = false) =>
    http.post<ImportResult>('/api/sources/import-url', { url, isPublic }),
  /** 下载链接里的书源并列出新增 / 更新 / 已有，勾选后用 importText 导入 */
  importPreview: (url: string) => http.post<ImportPreviewItem[]>('/api/sources/import-preview', { url }),
  create: (source: string, isPublic?: boolean) => http.post<{ id: number }>('/api/sources', { source, isPublic }),
  save: (id: number, source: string, isPublic?: boolean) =>
    http.put<{ id: number }>(`/api/sources/${id}`, { source, isPublic }),
  remove: (id: number) => http.del(`/api/sources/${id}`),
  batch: (ids: number[], action: string, value?: string) =>
    http.post('/api/sources/batch', { ids, action, value }),
  exportUrl: (ids?: number[]) => `/api/sources/export${ids?.length ? `?ids=${ids.join(',')}` : ''}`,
  loginConfig: (id: number) => http.get<LoginConfig>(`/api/sources/${id}/login`),
  login: (id: number, info: Record<string, string>) =>
    http.post<ActionResult>(`/api/sources/${id}/login`, { info }),
  /** 只保存登录界面中填写的内容 */
  saveLoginInfo: (id: number, info: Record<string, string>) => http.post(`/api/sources/${id}/login-info`, { info }),
  action: (id: number, action: string, info: Record<string, string>, isLongClick = false) =>
    http.post<ActionResult>(`/api/sources/${id}/action`, { action, info, isLongClick }),
  loginV2Render: (id: number, state?: string | null) =>
    http.post<LoginV2Render>(`/api/sources/${id}/login/v2/render`, { state }),
  loginV2Action: (id: number, action: string, state: string | null, form: Record<string, string>) =>
    http.post<LoginV2ActionResult>(`/api/sources/${id}/login/v2/action`, { action, state, form }),
  logout: (id: number) => http.del(`/api/sources/${id}/login`),
  setVariable: (id: number, variable: string) => http.put(`/api/sources/${id}/variable`, { variable }),
  /** 手动设置 / 查看书源网站的 Cookie（url 为空时用登录地址或书源地址） */
  getCookie: (id: number, url?: string) =>
    http.get<{ url: string; domain: string; cookie: string }>(`/api/sources/${id}/cookie${url ? `?url=${encodeURIComponent(url)}` : ''}`),
  setCookie: (id: number, cookie: string, url?: string) =>
    http.post<{ url: string; domain: string; cookie: string }>(`/api/sources/${id}/cookie`, { cookie, url }),
  exploreKinds: (id: number, refresh = false) =>
    http.get<{ kinds: ExploreKind[]; infoMap: Record<string, string> }>(`/api/sources/${id}/explore${refresh ? "?refresh=true" : ""}`),
  exploreAction: (id: number, body: { title: string; value?: string | null; action?: string | null }) =>
    http.post<ActionResult>(`/api/sources/${id}/explore/action`, body),
  explore: (sourceId: number, url: string, page: number) =>
    http.post<{ books: SearchBook[]; messages: string[]; openUrls: string[] }>("/api/explore", { sourceId, url, page }),
  debug: (
    body: { sourceId?: number; source?: string; key: string },
    onLog: (item: { state: number; msg: string }) => void,
    signal?: AbortSignal,
  ) => streamNdjson('POST', '/api/sources/debug', body, onLog, signal),
}

export const subscriptionApi = {
  list: () => http.get<Subscription[]>('/api/subscriptions'),
  create: (data: { name: string; url: string; isPublic: boolean; autoUpdate: boolean }) =>
    http.post<{ id: number }>('/api/subscriptions', data),
  refresh: (id: number) => http.post<ImportResult>(`/api/subscriptions/${id}/refresh`),
  remove: (id: number) => http.del(`/api/subscriptions/${id}`),
}

export type SearchEvent =
  | { type: 'books'; books: SearchBook[] }
  | { type: 'progress'; done: number; total: number; source: string; error?: string | null }
  | { type: 'end' }

export const searchApi = {
  search: (
    params: { key: string; page?: number; group?: string; sourceIds?: number[] },
    onEvent: (e: SearchEvent) => void,
    signal?: AbortSignal,
  ) => {
    const p = new URLSearchParams({ key: params.key, page: String(params.page ?? 1) })
    if (params.group) p.set('group', params.group)
    if (params.sourceIds?.length) p.set('sourceIds', params.sourceIds.join(','))
    return streamNdjson('GET', `/api/search?${p}`, undefined, onEvent, signal)
  },
  searchInSource: (sourceId: number, key: string) =>
    http.get<SearchBook[]>(`/api/search/source/${sourceId}?key=${encodeURIComponent(key)}`),
}

export interface OpenBookRequest {
  bookUrl: string
  origin: string
  sourceId?: number
  name?: string
  author?: string
  kind?: string | null
  coverUrl?: string | null
  intro?: string | null
  wordCount?: string | null
  latestChapterTitle?: string | null
  tocUrl?: string | null
  type?: number
  variable?: string | null
  inShelf?: boolean
  groupId?: number
}

export function toOpenRequest(b: SearchBook, inShelf = false): OpenBookRequest {
  return {
    bookUrl: b.bookUrl,
    origin: b.origin,
    sourceId: b.sourceId,
    name: b.name,
    author: b.author,
    kind: b.kind,
    coverUrl: b.coverUrl,
    intro: b.intro,
    wordCount: b.wordCount,
    latestChapterTitle: b.latestChapterTitle,
    tocUrl: b.tocUrl,
    type: b.type,
    variable: b.variable,
    inShelf,
  }
}

export const shelfApi = {
  list: () => http.get<Book[]>('/api/shelf'),
  open: (req: OpenBookRequest) => http.post<Book>('/api/shelf/open', req),
  refreshAll: () => http.post<{ message: string }>('/api/shelf/refresh'),
  get: (id: number) => http.get<Book>(`/api/shelf/${id}`),
  update: (id: number, data: Partial<Pick<Book, 'name' | 'author' | 'groupId' | 'inShelf' | 'customCoverUrl' | 'customIntro' | 'canUpdate' | 'order' | 'reSegment' | 'removeSameTitle' | 'useReplaceRule'>>) =>
    http.patch<Book>(`/api/shelf/${id}`, data),
  remove: (id: number) => http.del(`/api/shelf/${id}`),
  /** 手动排序：按新顺序排列的书籍 id */
  saveOrder: (ids: number[]) => http.put('/api/shelf/order', { ids }),
  refresh: (id: number, info = false) => http.post<Book>(`/api/shelf/${id}/refresh${info ? '?info=true' : ''}`),
  /** 本地 TXT 按指定目录规则重新分章；tocUrl 为空字符串时自动选择 */
  setTxtToc: (id: number, tocUrl: string) => http.post<Book>(`/api/shelf/${id}/txt-toc`, { tocUrl }),
  chapters: (id: number) => http.get<Chapter[]>(`/api/shelf/${id}/chapters`),
  content: (id: number, index: number, refresh = false) =>
    http.get<ChapterContent>(`/api/shelf/${id}/content/${index}${refresh ? '?refresh=true' : ''}`),
  /** time：记录进度的时间，传入时服务器只接受比已有进度新的 */
  progress: (id: number, index: number, pos: number, title?: string, time?: number) =>
    http.put<{ ok: boolean; message: string | null }>(`/api/shelf/${id}/progress`, { index, pos, title, time }),
  cache: (id: number, start: number, count: number) => http.post(`/api/shelf/${id}/cache`, { start, count }),
  /** 书内全文搜索（在线书籍只搜已缓存的章节） */
  search: (id: number, q: string) => http.get<BookSearchResult>(`/api/shelf/${id}/search?q=${encodeURIComponent(q)}`),
  clearCache: (id: number) => http.del(`/api/shelf/${id}/cache`),
  changeSource: (id: number, req: OpenBookRequest) => http.post<Book>(`/api/shelf/${id}/change-source`, req),
  /** 点击正文图片（段评、本章说等），src 为正文中的完整图片地址 */
  clickImage: (id: number, index: number, src: string) =>
    http.post<ActionResult>(`/api/shelf/${id}/content/${index}/click`, { src }),
  localInfo: () => http.get<LocalBookInfo>('/api/shelf/local/info'),
  /** 上传本地书籍（EPUB / TXT / MOBI / AZW3 / AZW / UMD / PDF），onProgress 为 0-1 */
  /** pages：PDF 的页数（浏览器读出后提交，服务器按页数生成目录） */
  uploadLocal: (file: File, groupId: number, onProgress?: (p: number) => void, pages?: number) =>
    new Promise<Book>((resolve, reject) => {
      const q = new URLSearchParams({ name: file.name, groupId: String(groupId) })
      if (pages) q.set('pages', String(pages))
      const xhr = new XMLHttpRequest()
      xhr.open('POST', `/api/shelf/local?${q}`)
      xhr.setRequestHeader('Authorization', `Bearer ${getToken()}`)
      xhr.setRequestHeader('Accept-Language', locale)
      xhr.setRequestHeader('Content-Type', 'application/octet-stream')
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress?.(e.loaded / e.total) }
      xhr.onload = () => {
        let data: any = null
        try { data = JSON.parse(xhr.responseText) } catch { /* ignore */ }
        if (xhr.status >= 200 && xhr.status < 300) resolve(data as Book)
        else reject(new ApiError(xhr.status, data?.error || t('上传失败（{0}）', [xhr.status])))
      }
      xhr.onerror = () => reject(new NetworkError())
      xhr.send(file)
    }),
  /** PDF 封面（浏览器渲染的第一页） */
  uploadLocalCover: (id: number, image: Blob) =>
    http.postBlob<Book>(`/api/shelf/${id}/local/cover`, image),
}

/** 书源原生段评（ruleReview）：para 为段落序号（从 1 开始，-1 为本章说） */
export interface ReviewSummary {
  counts: Record<string, number>
  keys: Record<string, string>
}

export interface ReviewItem {
  id: string | null
  avatar: string | null
  name: string | null
  replyToName: string | null
  badges: string[]
  content: string | null
  imageUrl: string | null
  audioUrl: string | null
  time: string | null
  likeCount: number | null
  replyCount: number | null
  replies: ReviewItem[]
}

export interface ReviewPage {
  items: ReviewItem[]
  nextCursor: string | null
  hasMore: boolean
}

const reviewQuery = (o: Record<string, string | number | null | undefined>) =>
  new URLSearchParams(Object.entries(o).filter(([, v]) => v != null && v !== '').map(([k, v]) => [k, String(v)])).toString()

export const reviewApi = {
  summary: (bookId: number, index: number) => http.get<ReviewSummary>(`/api/shelf/${bookId}/review/${index}/summary`),
  detail: (bookId: number, index: number, para: number, paraData?: string | null, page = 1, cursor?: string | null) =>
    http.get<ReviewPage>(`/api/shelf/${bookId}/review/${index}/detail?${reviewQuery({ para, paraData, page, cursor })}`),
  replies: (bookId: number, index: number, para: number, reviewId: string, paraData?: string | null, page = 1) =>
    http.get<ReviewPage>(`/api/shelf/${bookId}/review/${index}/replies?${reviewQuery({ para, paraData, reviewId, page })}`),
}

export const groupApi = {
  list: () => http.get<BookGroup[]>('/api/groups'),
  create: (name: string, order = 0) => http.post<BookGroup>('/api/groups', { name, order }),
  update: (id: number, name: string, order = 0) => http.put<BookGroup>(`/api/groups/${id}`, { name, order }),
  remove: (id: number) => http.del(`/api/groups/${id}`),
}

export const replaceApi = {
  list: () => http.get<ReplaceRule[]>('/api/replace-rules'),
  save: (r: Partial<ReplaceRule>) => http.post<ReplaceRule>('/api/replace-rules', r),
  import: (json: string) => http.postRaw<{ count: number }>('/api/replace-rules/import', json),
  remove: (ids: number[]) => http.post('/api/replace-rules/delete', { ids }),
}

/** 字典规则（与 App 格式相同）；canEdit 为 false 时只能启用/停用、排序 */
export const dictApi = {
  list: () => http.get<{ rules: DictRule[]; canEdit: boolean }>('/api/dict-rules'),
  save: (rule: DictRule, oldName?: string) => http.post<DictRule>('/api/dict-rules', { rule, oldName }),
  remove: (names: string[]) => http.post('/api/dict-rules/delete', { names }),
  import: (json: string) => http.postRaw<{ count: number }>('/api/dict-rules/import', json),
  importDefaults: () => http.post('/api/dict-rules/defaults'),
  search: (name: string, word: string) => http.post<{ html: string; baseUrl: string | null }>('/api/dict-rules/search', { name, word }),
}

/** TXT 目录规则（与 App 格式相同）；canEdit 为 false 时不能保存带 js 处理的规则 */
export const txtTocApi = {
  list: () => http.get<{ rules: TxtTocRule[]; canEdit: boolean }>('/api/txt-toc-rules'),
  save: (rule: TxtTocRule) => http.post<TxtTocRule>('/api/txt-toc-rules', rule),
  remove: (ids: number[]) => http.post('/api/txt-toc-rules/delete', { ids }),
  import: (json: string) => http.postRaw<{ count: number }>('/api/txt-toc-rules/import', json),
  importDefaults: () => http.post('/api/txt-toc-rules/defaults'),
}

/** 与阅读 App 互通的备份 */
export interface BackupParts {
  books: boolean
  sources: boolean
  rules: boolean
  subs: boolean
}

export interface BackupImportSummary {
  booksAdded: number
  booksUpdated: number
  booksSkipped: number
  groupsAdded: number
  sourcesAdded: number
  sourcesUpdated: number
  sourcesSkipped: number
  rulesAdded: number
  dictRulesImported: number
  tocRulesImported: number
  bookmarksAdded?: number
  subsAdded: number
  files: string[]
  warnings: string[]
}

async function backupRequest(method: string, parts: BackupParts, body?: Blob): Promise<Response> {
  const q = new URLSearchParams(Object.entries(parts).map(([k, v]) => [k, String(v)]))
  const url = method === "GET" ? `/api/backup/export?${q}` : `/api/backup/import?${q}`
  let res: Response
  try {
    res = await fetch(url, {
      method,
      headers: { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale, ...(body ? { "Content-Type": "application/zip" } : {}) },
      body,
    })
  } catch {
    throw new NetworkError()
  }
  if (!res.ok) {
    let msg = t('请求失败 ({0})', [res.status])
    try {
      msg = (await res.json()).error || msg
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, msg)
  }
  return res
}

/** 音频书籍：音频由服务器转发（带书源请求头、Cookie，支持拖动进度） */
export const audioApi = {
  info: (id: number, index: number, refresh = false) =>
    http.get<{ hls: boolean }>(`/api/shelf/${id}/audio/${index}/info${refresh ? '?refresh=1' : ''}`),
  src: (id: number, index: number, refresh = false) => {
    const q = new URLSearchParams({ token: getToken() })
    if (refresh) q.set('refresh', '1')
    return `/api/shelf/${id}/audio/${index}?${q}`
  },
}

/** 在线朗读引擎（App 的 httpTTS） */
export const statsApi = {
  /** 上报阅读时长、字数的增量；day 为本地日期 yyyy-MM-dd */
  report: (r: { day: string; bookId: number; seconds: number; chars: number }) => http.post('/api/stats/reading', r),
  summary: (from: string) => http.get<ReadingSummary>(`/api/stats/reading?from=${from}`),
}

export const bookmarkApi = {
  list: (bookId: number) => http.get<Bookmark[]>(`/api/shelf/${bookId}/bookmarks`),
  add: (bookId: number, b: Pick<Bookmark, 'chapterIndex' | 'chapterName' | 'chapterPos' | 'bookText' | 'content'>) =>
    http.post<Bookmark>(`/api/shelf/${bookId}/bookmarks`, b),
  update: (id: number, content: string) => http.put(`/api/bookmarks/${id}`, { content }),
  remove: (ids: number[]) => http.post('/api/bookmarks/delete', { ids }),
}

/** 阅读字体（用户上传，在所有设备上可用） */
export interface UserFont {
  id: number
  name: string
  format: string
  size: number
}

export const fontApi = {
  list: () => http.get<UserFont[]>('/api/fonts'),
  /** 上传字体文件（TTF、OTF、WOFF、WOFF2、TTC，最大 30MB） */
  upload: async (file: File): Promise<UserFont> => {
    let res: Response
    try {
      res = await fetch(`/api/fonts?name=${encodeURIComponent(file.name)}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale, 'Content-Type': 'application/octet-stream' },
        body: file,
      })
    } catch {
      throw new NetworkError()
    }
    const data = await res.json().catch(() => null)
    if (!res.ok) throw new ApiError(res.status, data?.error || t('上传失败（{0}）', [res.status]))
    return data as UserFont
  },
  remove: (id: number) => http.del(`/api/fonts/${id}`),
  /** 字体文件内容（浏览器会长期缓存） */
  file: async (id: number): Promise<ArrayBuffer> => {
    const res = await fetch(`/api/fonts/${id}/file`, { headers: { Authorization: `Bearer ${getToken()}` } })
    if (!res.ok) throw new ApiError(res.status, t('字体加载失败（{0}）', [res.status]))
    return res.arrayBuffer()
  },
}

export const ttsApi = {
  list: () => http.get<TtsEngine[]>('/api/tts'),
  import: (text: string) => http.postRaw<{ added: number; updated: number }>('/api/tts/import', text),
  export: () => http.get<unknown[]>('/api/tts/export'),
  remove: (id: number) => http.del(`/api/tts/${id}`),
  get: (id: number) => http.get<TtsEngineConfig>(`/api/tts/${id}`),
  /** 新建（没有 id）或修改 */
  save: (e: TtsEngineConfig) => (e.id ? http.put<TtsEngineConfig>(`/api/tts/${e.id}`, e) : http.post<TtsEngineConfig>('/api/tts', e)),
  /** 合成一段语音，返回音频 */
  speak: (id: number, text: string, speed: number, signal?: AbortSignal) =>
    ttsAudio(`/api/tts/${id}/speak`, { text, speed }, signal),
  /** 用编辑中（还没保存）的引擎试听 */
  test: (engine: TtsEngineConfig, text: string, speed = 10) => ttsAudio('/api/tts/test', { engine, text, speed }),
}

/** 请求朗读引擎合成语音，返回音频；出错时把服务器的错误信息作为异常 */
async function ttsAudio(url: string, body: unknown, signal?: AbortSignal): Promise<Blob> {
  let res: Response
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    })
  } catch (e: any) {
    if (e?.name === 'AbortError') throw e
    throw new NetworkError()
  }
  if (!res.ok) {
    let msg = t('朗读失败（{0}）', [res.status])
    try {
      msg = (await res.json()).error || msg
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, msg)
  }
  return res.blob()
}

export const backupApi = {
  /** 下载备份 zip，返回文件名和内容 */
  export: async (parts: BackupParts) => {
    const res = await backupRequest("GET", parts)
    const cd = res.headers.get("content-disposition") || ""
    const name = /filename="?([^";]+)"?/.exec(cd)?.[1] || "backup.zip"
    return { name, blob: await res.blob() }
  },
  import: async (file: Blob, parts: BackupParts) =>
    (await (await backupRequest("POST", parts, file)).json()) as BackupImportSummary,
}

export interface WebDavConfig {
  url: string
  account: string
  hasPassword: boolean
  dir: string
  deviceName: string
  autoBackup: boolean
  lastBackup: number
  lastError: string | null
}

export interface WebDavConfigInput {
  url: string
  account: string
  /** 留空表示不修改 */
  password?: string
  dir: string
  deviceName: string
  autoBackup: boolean
}

export interface WebDavFile {
  name: string
  size: number
  lastModified: number
  isDir: boolean
}

const partsQuery = (p: BackupParts) => new URLSearchParams(Object.entries(p).map(([k, v]) => [k, String(v)])).toString()

export const webdavApi = {
  config: () => http.get<WebDavConfig>("/api/webdav/config"),
  save: (c: WebDavConfigInput) => http.put<WebDavConfig>("/api/webdav/config", c),
  clear: () => http.del("/api/webdav/config"),
  backup: (p: BackupParts) => http.post<{ name: string; size: number }>(`/api/webdav/backup?${partsQuery(p)}`),
  list: () => http.get<WebDavFile[]>("/api/webdav/backups"),
  restore: (name: string, p: BackupParts) =>
    http.post<BackupImportSummary>(`/api/webdav/restore?${partsQuery(p)}`, { name }),
}

export const prefsApi = {
  get: async <T>(key: string): Promise<T | null> => {
    const r = await http.get<{ value: string | null }>(`/api/prefs/${key}`)
    if (!r.value) return null
    try {
      return JSON.parse(r.value) as T
    } catch {
      return null
    }
  },
  set: (key: string, value: unknown) => http.putRaw(`/api/prefs/${key}`, JSON.stringify(value)),
}

/** 书源登录 / 按钮操作的结果 */
export interface ActionResult {
  ok: boolean
  message: string | null
  result?: string | null
  /** 脚本中 java.toast 的提示 */
  messages: string[]
  /** 脚本请求打开的网页 */
  openUrls: string[]
  /** 脚本请求的界面刷新：reLoginView / refreshExplore 等 */
  events: string[]
  /** 脚本通过 upLoginData 更新的表单值 */
  uiData: Record<string, string | null>
  /** 脚本通过 copyText 要复制的文本 */
  clipboard?: string | null
}

export interface LoginV2ActionResult {
  ok: boolean
  message: string | null
  errors: Record<string, string>
  close: boolean
  render: LoginV2Render | null
  effects: ActionResult | null
}

export interface ApiKeyInfo {
  id: number
  name: string
  prefix: string
  createdAt: number
  lastUsedAt: number | null
}

export const apiKeyApi = {
  list: () => http.get<ApiKeyInfo[]>('/api/apikeys'),
  /** 明文 key 只在创建时返回一次 */
  create: (name: string) => http.post<{ key: string; info: ApiKeyInfo }>('/api/apikeys', { name }),
  remove: (id: number) => http.del(`/api/apikeys/${id}`),
}
