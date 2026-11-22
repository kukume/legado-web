import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { useUiStore } from '@/stores/ui'
import { findTheme, isDarkTheme, type ReaderConfig } from '@/stores/reader'

/**
 * 上次在阅读页看到的深浅色模式。只存在这台设备上：阅读设置会在多台设备间同步，
 * 但每台设备的深浅色模式各不相同，存到设置里会让手机和电脑互相触发提示
 */
const LAST_KEY = 'legado_reader_last_dark'
/** 提示条自动收起的时间 */
const HIDE_AFTER = 10_000

function readLast(): boolean | null {
  try {
    const v = localStorage.getItem(LAST_KEY)
    return v === 'dark' ? true : v === 'light' ? false : null
  } catch {
    return null
  }
}

function saveLast(dark: boolean) {
  try {
    localStorage.setItem(LAST_KEY, dark ? 'dark' : 'light')
  } catch {
    /* ignore */
  }
}

/**
 * 正文主题跟随系统深浅色模式：
 * - 检测到深浅色模式变化（阅读时切换，或上次离开阅读页后切换过），而正文主题的深浅与之不符时，
 *   按设置提示一次（ask）、自动切换（auto）或不处理（off）
 * - 同一次变化只处理一次；页面在后台时等回到页面再处理
 * - 切换时用最近使用过的同类主题（浅色回到之前的羊皮纸等，深色用之前的夜间、雾灰等）
 */
export function useThemeFollow(cfg: Readonly<Ref<ReaderConfig>>) {
  const ui = useUiStore()
  /** 显示提示条时为要切换到的模式（true 为深色），不显示时为 null */
  const prompt = ref<boolean | null>(null)
  let started = false
  let hideTimer: ReturnType<typeof setTimeout> | null = null

  const currentDark = () => {
    const th = findTheme(cfg.value, cfg.value.theme)
    return th ? isDarkTheme(th) : false
  }

  // 记住最近使用的浅色、深色主题
  watch(
    () => cfg.value.theme,
    (key) => {
      const th = findTheme(cfg.value, key)
      if (!th) return
      if (isDarkTheme(th)) {
        if (cfg.value.nightTheme !== key) cfg.value.nightTheme = key
      } else if (cfg.value.dayTheme !== key) {
        cfg.value.dayTheme = key
      }
      // 主题变了（手动切换或已按提示切换），提示条不再需要
      hide()
    },
    { immediate: true },
  )

  /** 切到深色或浅色时使用的主题：最近用过的同类主题，没有（或已删除、已改成另一类）时用夜间 / 羊皮纸 */
  function targetTheme(dark: boolean) {
    const key = dark ? cfg.value.nightTheme : cfg.value.dayTheme
    const th = findTheme(cfg.value, key)
    return th && isDarkTheme(th) === dark ? key! : dark ? 'night' : 'paper'
  }

  function hide() {
    prompt.value = null
    if (hideTimer) clearTimeout(hideTimer)
    hideTimer = null
  }

  function apply(dark: boolean) {
    cfg.value.theme = targetTheme(dark)
    hide()
  }

  function check() {
    if (!started || document.visibilityState === 'hidden') return
    const now = ui.isDark
    const last = readLast()
    if (last === now) return
    saveLast(now)
    // 这台设备第一次打开阅读页：只记录，不算变化
    if (last === null) return
    if (currentDark() === now) return
    const mode = cfg.value.followSystem ?? 'ask'
    if (mode === 'auto') {
      apply(now)
    } else if (mode === 'ask') {
      hide()
      prompt.value = now
      hideTimer = setTimeout(hide, HIDE_AFTER)
    }
  }

  watch(() => ui.isDark, check)
  document.addEventListener('visibilitychange', check)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', check)
    if (hideTimer) clearTimeout(hideTimer)
  })

  return {
    prompt,
    /** 阅读设置加载完成后开始检测（避免用本地旧设置判断后又被服务器上的设置覆盖） */
    start() {
      started = true
      check()
    },
    /** 切换日间 / 夜间（阅读菜单的按钮）：切到最近使用的另一类主题 */
    toggle() {
      apply(!currentDark())
    },
    /** 这次切换 */
    switchNow() {
      if (prompt.value !== null) apply(prompt.value)
    },
    /** 以后自动切换 */
    always() {
      cfg.value.followSystem = 'auto'
      if (prompt.value !== null) apply(prompt.value)
    },
    /** 不再提示 */
    never() {
      cfg.value.followSystem = 'off'
      hide()
    },
    dismiss: hide,
  }
}
