import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useIsMobile } from '@/composables/useIsMobile'
import { navItems } from '@/utils/nav'

/**
 * 侧栏、底栏显示的入口，以及当前设备的导航栏里没有显示的入口（在“我的”里列出，进入后显示返回按钮）。
 * Z-Library 需管理员开启，管理只对管理员显示；书架、搜索、书源、我的固定显示；手机底栏始终不显示管理
 */
export function useNavBars() {
  const auth = useAuthStore()
  const ui = useUiStore()
  const isMobile = useIsMobile()

  const available = computed(() =>
    navItems().filter((i) => (i.name !== 'zlibrary' || !!auth.me?.zlibEnabled) && (i.name !== 'admin' || auth.isAdmin)),
  )
  const sideNav = computed(() => available.value.filter((i) => i.fixed || !ui.navHidden.side.includes(i.name)))
  const bottomNav = computed(() => available.value.filter((i) => !i.sideOnly && (i.fixed || !ui.navHidden.bottom.includes(i.name))))

  /** 当前设备（手机看底栏、电脑看侧栏）的导航栏里没有显示的入口 */
  const hiddenHere = computed(() => {
    const shown = new Set((isMobile.value ? bottomNav.value : sideNav.value).map((i) => i.name))
    return available.value.filter((i) => !shown.has(i.name))
  })

  return { available, sideNav, bottomNav, hiddenHere, isMobile }
}
