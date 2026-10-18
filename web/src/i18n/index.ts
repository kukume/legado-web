/**
 * 界面语言：简体中文（原文）、繁体中文、English。
 *
 * - 直接用简体中文原文作为 key：t('共 {0} 章', [n])；没有翻译时显示原文
 * - 参数：数组对应 {0} {1}…，对象对应 {name}
 * - 翻译文件：zh-TW.json（由 scripts/i18n.mjs 用 OpenCC 从原文生成后校对）、en.json（手工翻译）
 * - 切换语言时刷新页面，组件里在模块顶层用 t() 生成的文字也能更新
 * - 新增或修改文字后运行 npm run i18n 检查缺少的翻译
 */
export type Locale = 'zh-CN' | 'zh-TW' | 'en'

export const LOCALES: { value: Locale; label: string }[] = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁體中文' },
  { value: 'en', label: 'English' },
]

const STORAGE_KEY = 'legado_locale'

function detect(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh-CN' || saved === 'zh-TW' || saved === 'en') return saved
  } catch {
    // 隐私模式等情况下读不到本地存储，按浏览器语言
  }
  const langs = typeof navigator !== 'undefined' ? navigator.languages?.length ? navigator.languages : [navigator.language] : []
  for (const raw of langs) {
    const l = (raw || '').toLowerCase()
    if (/^zh-(tw|hk|mo)|^zh-hant/.test(l)) return 'zh-TW'
    if (l.startsWith('zh')) return 'zh-CN'
    if (l.startsWith('en')) return 'en'
  }
  return langs.length ? 'en' : 'zh-CN'
}

/** 当前语言（页面加载时确定，切换后刷新页面） */
export const locale: Locale = detect()

let messages: Record<string, string> = {}

/** 在挂载应用前加载当前语言的翻译 */
export async function initI18n() {
  document.documentElement.lang = locale === 'zh-TW' ? 'zh-Hant' : locale === 'en' ? 'en' : 'zh-Hans'
  if (locale === 'zh-TW') messages = (await import('./zh-TW.json')).default as Record<string, string>
  else if (locale === 'en') messages = (await import('./en.json')).default as Record<string, string>
  // index.html 里的默认标题；加载站点配置后会换成管理员设置的站点名称
  if (document.title === '阅读 Web') document.title = t('阅读 Web')
}

export function setLocale(l: Locale) {
  try {
    localStorage.setItem(STORAGE_KEY, l)
  } catch {
    // 存不下时只对本次生效
  }
  location.reload()
}

type Params = (string | number | null | undefined)[] | Record<string, string | number | null | undefined>

/** 翻译：text 为简体中文原文 */
export function t(text: string, params?: Params): string {
  const s = messages[text] ?? text
  if (!params) return s
  return s.replace(/\{(\w+)\}/g, (m, k: string) => {
    const v = Array.isArray(params) ? params[Number(k)] : params[k]
    return v === undefined || v === null ? m : String(v)
  })
}

/** 英文界面（用于少数需要不同排版的地方，例如数字和单位之间的空格） */
export const isEn = locale === 'en'

const compactFormat = new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 })
/** 大数字的简写：中文 1.2万 / 1.2萬，英文 12K（一万以下中文原样显示） */
export function compactNumber(n: number): string {
  return compactFormat.format(n)
}
