import type { FlexChildStyle } from '@/utils/flex'

export interface User {
  id: number
  username: string
  role: 'admin' | 'user'
  enabled: boolean
  createdAt: number
  lastLoginAt: number | null
  invitedBy: number | null
  /** 单独授权添加书源 */
  canAddSource: boolean
}

export interface Me {
  user: User
  canAddSource: boolean
}

export interface PublicConfig {
  siteName: string
  registrationMode: 'invite' | 'closed'
  hasUsers: boolean
  /** 登录、注册需要的人机验证；null 表示不需要 */
  captcha: { provider: 'cap'; endpoint: string } | null
}

export interface Invite {
  id: number
  code: string
  createdBy: number
  createdByName: string | null
  maxUses: number
  usedCount: number
  expiresAt: number | null
  note: string | null
  disabled: boolean
  createdAt: number
}

export interface SourceSummary {
  id: number
  bookSourceUrl: string
  bookSourceName: string
  bookSourceGroup: string | null
  bookSourceType: number
  enabled: boolean
  enabledExplore: boolean
  hasExplore: boolean
  hasLogin: boolean
  customOrder: number
  respondTime: number
  isPublic: boolean
  owned: boolean
  canEdit: boolean
  ownerId: number
  bookSourceComment: string | null
  lastUpdateTime: number
}

export interface ImportResult {
  added: number
  updated: number
  failed: number
  errors: string[]
}

export interface SearchBook {
  name: string
  author: string
  kind?: string | null
  coverUrl?: string | null
  intro?: string | null
  wordCount?: string | null
  latestChapterTitle?: string | null
  bookUrl: string
  origin: string
  originName: string
  sourceId: number
  type: number
  tocUrl: string
  variable?: string | null
  respondTime: number
}

/** 合并后的搜索结果 */
export interface MergedBook extends SearchBook {
  key: string
  sources: SearchBook[]
}

export interface Book {
  id: number
  bookUrl: string
  tocUrl: string
  origin: string
  originName: string
  name: string
  author: string
  kind: string | null
  coverUrl: string | null
  intro: string | null
  type: number
  groupId: number
  inShelf: boolean
  latestChapterTitle: string | null
  latestChapterTime: number
  lastCheckTime: number
  lastCheckCount: number
  totalChapterNum: number
  durChapterTitle: string | null
  durChapterIndex: number
  durChapterPos: number
  durChapterTime: number
  wordCount: string | null
  canUpdate: boolean
  order: number
  customCoverUrl: string | null
  customIntro: string | null
  sourceId: number | null
  /** 书源是否提供段评（ruleReview） */
  hasReview?: boolean
  /** 本地上传的书籍（EPUB / TXT / MOBI / AZW3 / AZW / UMD / PDF） */
  isLocal?: boolean
  /** 音频书籍（音频书源，正文是音频地址；durChapterPos 为毫秒） */
  isAudio?: boolean
  /** 视频（短剧等，视频书源；durChapterPos 为毫秒） */
  isVideo?: boolean
  /** 图片（漫画，图片书源） */
  isImage?: boolean
  /** 正文处理（按书保存）：重新分段、删除重复标题、替换净化（使用替换规则） */
  reSegment?: boolean
  removeSameTitle?: boolean
  useReplaceRule?: boolean
}

export interface TtsEngine {
  id: number
  name: string
  url: string
  contentType: string | null
  hasLogin: boolean
  /** 自己导入的；否则是管理员提供的公共引擎 */
  owned: boolean
}

/** 在线朗读引擎的完整内容（与阅读 App 的 httpTTS 格式相同） */
export interface TtsEngineConfig {
  id?: number
  name: string
  url: string
  contentType?: string | null
  concurrentRate?: string | null
  loginUrl?: string | null
  loginUi?: string | null
  loginCheckJs?: string | null
  header?: string | null
  jsLib?: string | null
  enabledCookieJar?: boolean | null
  lastUpdateTime?: number
}

export interface LocalBookInfo {
  enabled: boolean
  maxMb: number
  quotaMb: number
  usedBytes: number
}

export interface Chapter {
  index: number
  title: string
  isVolume: boolean
  isVip: boolean
  isPay: boolean
  tag: string | null
  cached: boolean
}

export interface ChapterContent {
  index: number
  title: string
  content: string
  isVolume: boolean
  cached: boolean
  /** 正文实际上是视频地址（聚合书源的短剧等） */
  video?: boolean
  /** 分页正文的后续页面获取失败，内容不完整 */
  incomplete?: boolean
}

export interface BookGroup {
  id: number
  name: string
  order: number
}

export interface RowUi {
  name: string
  /** text / password / button / select / toggle / label */
  type: string
  action?: string | null
  default?: string | null
  chars?: (string | null)[] | null
  viewName?: string | null
  style?: FlexChildStyle | null
  /** 以下为登录界面 v2 字段 */
  key?: string | null
  hint?: string | null
  value?: string | null
  options?: string[] | null
  countdown?: number | null
}

export interface LoginConfig {
  sourceId: number
  sourceName: string
  /** none / v1 / v2 */
  mode: "none" | "v1" | "v2"
  loginUi: RowUi[]
  loginInfo: Record<string, string>
  hasLoginUrl: boolean
  loginUrl: string | null
  loginHeader: string | null
  variable: string
  variableComment: string | null
  renderError?: string | null
}

export interface LoginV2Render {
  state: string
  rows: RowUi[]
  stored: Record<string, string>
}

export interface ExploreKind {
  title: string
  url: string | null
  /** url / text / button / toggle / select */
  type: string
  action?: string | null
  chars: string[]
  default?: string | null
  /** viewName 计算后的显示名 */
  displayName: string
  style?: FlexChildStyle | null
}

export interface Subscription {
  id: number
  name: string
  url: string
  isPublic: boolean
  autoUpdate: boolean
  lastUpdateAt: number
  lastResult: string | null
  owned: boolean
}

/** 书内搜索的一条结果 */
export interface BookSearchHit {
  chapterIndex: number
  chapterTitle: string
  /** 关键字前后的一小段文字 */
  snippet: string
  /** 关键字在 snippet 中的位置 */
  matchStart: number
  matchLength: number
  /** 章内位置（比例，0..10000） */
  pos: number
}

export interface BookSearchResult {
  /** 章节总数（不含卷名） */
  total: number
  /** 实际搜索的章节数（在线书籍只搜已缓存的章节） */
  searched: number
  hits: BookSearchHit[]
  /** 结果太多，只返回了前面一部分 */
  truncated: boolean
}

export interface ReadingSummary {
  totalSeconds: number
  totalChars: number
  totalDays: number
  days: { day: string; seconds: number; chars: number }[]
  books: { bookId: number; name: string; author: string; seconds: number; chars: number }[]
}

export interface Bookmark {
  id: number
  bookId: number
  chapterIndex: number
  chapterName: string
  /** 章内位置（比例，0..10000） */
  chapterPos: number
  /** 选中的正文 */
  bookText: string
  /** 备注 */
  content: string
  createdAt: number
}

/** 字典规则（App 的 DictRule） */
export interface DictRule {
  name: string
  urlRule: string
  showRule: string
  enabled: boolean
  sortNumber: number
}

/** TXT 目录规则（App 的 TxtTocRule），自带规则的 id 为负数 */
export interface TxtTocRule {
  id: number
  name: string
  rule: string
  replacement: string
  example: string | null
  serialNumber: number
  enable: boolean
}

/** App 保存在 book.tocUrl 中的目录规则分隔符：规则 + 分隔符 + js 处理 */
export const TXT_TOC_SEPARATOR = '🫅🈳🏻'

export interface ReplaceRule {
  id: number
  name: string
  group: string | null
  pattern: string
  replacement: string
  scope: string | null
  scopeTitle: boolean
  scopeContent: boolean
  excludeScope: string | null
  isEnabled: boolean
  isRegex: boolean
  order: number
}
