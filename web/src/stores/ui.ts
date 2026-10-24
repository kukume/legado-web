import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { usePreferredDark } from '@/utils/media'
import { syncAppThemeColor } from '@/utils/themeColor'

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

  return { themeMode, isDark, searchGroup }
})
