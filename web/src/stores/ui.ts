import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { usePreferredDark } from '@/utils/media'
import { syncAppThemeColor } from '@/utils/themeColor'
import { prefsApi } from '@/api'
import { DEFAULT_NAV_LAYOUT, normalizeNavLayout, type NavLayout } from '@/utils/nav'

type ThemeMode = 'auto' | 'light' | 'dark'

function load<T>(key: string, def: T): T {
  try {
    const v = localStorage.getItem(key)
    return v ? (JSON.parse(v) as T) : def
  } catch {
    return def
  }
}

function save(key: string, v: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(v))
  } catch {
    /* ignore */
  }
}

export const useUiStore = defineStore('ui', () => {
  const themeMode = ref<ThemeMode>(load('legado_theme', 'auto'))
  const systemDark = usePreferredDark()
  const isDark = computed(() => themeMode.value === 'dark' || (themeMode.value === 'auto' && systemDark.value))

  watch(themeMode, (v) => save('legado_theme', v))
  watch(
    isDark,
    (d) => {
      document.documentElement.dataset.theme = d ? 'dark' : 'light'
      syncAppThemeColor()
    },
    { immediate: true },
  )

  /** 搜索时选择的书源分组 */
  const searchGroup = ref<string>(load('legado_search_group', ''))
  watch(searchGroup, (v) => save('legado_search_group', v))

  /**
   * 侧栏、底栏隐藏的入口：保存在账号的偏好里（换设备也一样），本地缓存一份，打开网页时先按缓存显示
   */
  const NAV_KEY = 'legado_nav_layout'
  const navHidden = ref<NavLayout>(normalizeNavLayout(load(NAV_KEY, null)) ?? DEFAULT_NAV_LAYOUT)

  function setNavHidden(v: NavLayout) {
    navHidden.value = v
    save(NAV_KEY, v)
    return prefsApi.set('navLayout', v)
  }

  /** 登录后从服务器读取（没有保存过时保持默认） */
  async function loadNavLayout() {
    const v = normalizeNavLayout(await prefsApi.get('navLayout').catch(() => null))
    if (v) {
      navHidden.value = v
      save(NAV_KEY, v)
    }
  }

  return { themeMode, isDark, searchGroup, navHidden, setNavHidden, loadNavLayout }
})
