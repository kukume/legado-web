import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { prefsApi } from '@/api'
import { defaultKeyBindings, normalizeKeyBindings, type KeyBindings } from '@/utils/keys'
import { isLight, mix } from '@/utils/color'
import { t } from '@/i18n'

export interface ReaderTheme {
  key: string
  name: string
  bg: string
  text: string
  /** 工具栏/面板背景 */
  panel: string
  /** 纸张外的底色（宽屏时正文显示在居中的纸张上） */
  desk: string
}

/**
 * 自定义主题（只有颜色）。panel、desk 留空时由背景色自动推算
 */
export interface CustomTheme {
  id: string
  name: string
  bg: string
  text: string
  panel?: string
  desk?: string
  /** 早期版本的背景图片（已不再使用，保存 / 删除主题时清理） */
  image?: string
}

/** 自定义主题在 theme 设置中的值 */
export const customThemeKey = (id: string) => `custom:${id}`

/** 自定义主题转为完整的主题颜色：没有填写的面板色、底色由背景色推算 */
export function customToTheme(c: CustomTheme): ReaderTheme {
  const light = isLight(c.bg)
  return {
    key: customThemeKey(c.id),
    name: c.name,
    bg: c.bg,
    text: c.text,
    panel: c.panel || (light ? mix(c.bg, '#ffffff', 0.45) : mix(c.bg, '#ffffff', 0.08)),
    desk: c.desk || (light ? mix(c.bg, '#000000', 0.08) : mix(c.bg, '#000000', 0.3)),
  }
}

export const readerThemes: ReaderTheme[] = [
  { key: 'paper', name: t('羊皮纸'), bg: '#f3ead7', text: '#3b3226', panel: '#fbf6ea', desk: '#e4d9c1' },
  { key: 'white', name: t('白色'), bg: '#ffffff', text: '#222222', panel: '#f7f7f7', desk: '#ebebeb' },
  { key: 'green', name: t('护眼'), bg: '#cfe6cf', text: '#233423', panel: '#e3f1e3', desk: '#bad5ba' },
  { key: 'blue', name: t('浅蓝'), bg: '#dce9f5', text: '#1f2d3a', panel: '#ebf3fa', desk: '#c6d7e8' },
  { key: 'gray', name: t('雾灰'), bg: '#3a3a3c', text: '#d8d8d8', panel: '#48484b', desk: '#2b2b2d' },
  { key: 'night', name: t('夜间'), bg: '#141414', text: '#8f8a80', panel: '#1f1f1f', desk: '#0a0a0a' },
]

export const readerFonts = [
  { label: t('系统默认'), value: 'system' },
  { label: t('宋体 / 衬线'), value: 'serif' },
  { label: t('楷体'), value: 'kai' },
  { label: t('黑体'), value: 'sans' },
]

export function fontFamily(v: string) {
  switch (v) {
    case 'serif':
      return '"Noto Serif SC", "Source Han Serif SC", "Songti SC", SimSun, serif'
    case 'kai':
      return '"LXGW WenKai", "Kaiti SC", STKaiti, KaiTi, serif'
    case 'sans':
      return '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'
    default:
      return 'inherit'
  }
}

export interface ReaderConfig {
  theme: string
  /** 自定义主题 */
  customThemes?: CustomTheme[]
  /** 切到夜间前使用的主题（切回日间时恢复） */
  dayTheme?: string
  fontSize: number
  lineHeight: number
  paragraphSpacing: number
  letterSpacing: number
  fontFamily: string
  /** 正文最大宽度（px） */
  width: number
  /** scroll: 上下滚动；page: 左右翻页；vpage: 上下翻页 */
  mode: 'scroll' | 'page' | 'vpage'
  indent: boolean
  /** 点击左侧也向后翻页（单手模式） */
  tapNextOnLeft: boolean
  fontWeight: number
  /** 快捷键 */
  keys: KeyBindings
  /** 朗读引擎：browser 为浏览器自带语音，http:{id} 为在线朗读引擎 */
  ttsEngine?: string
  /** 朗读语速（1 为正常） */
  ttsRate?: number
  /** 浏览器语音名称（为空时自动选择中文语音） */
  ttsVoice?: string
  /** 阅读时保持屏幕常亮 */
  keepScreenOn?: boolean
  /** 双页显示（左右翻页）：auto 为横向宽屏时左右两页并排，off 为始终单页 */
  doublePage?: 'auto' | 'off'
  /** 翻页动画（翻页模式）：slide 平移；cover 覆盖；curl 仿真；fade 淡入淡出；none 无 */
  pageAnim?: PageAnim
  /** 自动滚动速度（像素 / 秒，上下滚动模式） */
  autoScrollSpeed?: number
  /** 自动翻页间隔（秒，翻页模式） */
  autoPageSeconds?: number
  /** 设置版本，用于迁移旧的默认值 */
  version?: number
}

export type PageAnim = 'slide' | 'cover' | 'curl' | 'fade' | 'none'

const defaults: ReaderConfig = {
  theme: 'paper',
  fontSize: 19,
  lineHeight: 1.8,
  paragraphSpacing: 0.6,
  letterSpacing: 0.02,
  fontFamily: 'system',
  width: 670,
  mode: 'scroll',
  indent: true,
  tapNextOnLeft: false,
  fontWeight: 400,
  keys: defaultKeyBindings(),
  ttsEngine: 'browser',
  ttsRate: 1,
  ttsVoice: '',
  keepScreenOn: true,
  pageAnim: 'slide',
  doublePage: 'auto',
  autoScrollSpeed: 40,
  autoPageSeconds: 15,
  version: 2,
}

/** 旧版默认页宽 760，迁移为新的默认 670（用户手动调过的不变） */
function migrate(c: ReaderConfig): ReaderConfig {
  c = { ...c, keys: normalizeKeyBindings(c.keys) }
  if ((c.version ?? 1) < 2) return { ...c, width: c.width === 760 ? 670 : c.width, version: 2 }
  return c
}

const LOCAL_KEY = 'legado_reader_config'

export const useReaderStore = defineStore('reader', () => {
  let initial = migrate({ ...defaults })
  try {
    const s = localStorage.getItem(LOCAL_KEY)
    if (s) initial = migrate({ ...defaults, version: 1, ...JSON.parse(s) })
  } catch {
    /* ignore */
  }
  const config = ref<ReaderConfig>(initial)
  let syncTimer: ReturnType<typeof setTimeout> | null = null
  let loadedRemote = false

  watch(
    config,
    (v) => {
      try {
        localStorage.setItem(LOCAL_KEY, JSON.stringify(v))
      } catch {
        /* ignore */
      }
      if (!loadedRemote) return
      if (syncTimer) clearTimeout(syncTimer)
      syncTimer = setTimeout(() => prefsApi.set('readConfig', v).catch(() => {}), 1500)
    },
    { deep: true },
  )

  /** 从服务器同步阅读设置（多设备共享） */
  async function loadRemote() {
    try {
      const remote = await prefsApi.get<ReaderConfig>('readConfig')
      if (remote) config.value = migrate({ ...defaults, version: 1, ...remote })
    } catch {
      /* ignore */
    } finally {
      loadedRemote = true
    }
  }

  function reset() {
    config.value = { ...defaults, keys: defaultKeyBindings() }
  }

  return { config, loadRemote, reset }
})
