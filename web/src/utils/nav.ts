import type { Component } from 'vue'
import {
  LibraryOutline, SearchOutline, CompassOutline, CodeSlashOutline, PersonCircleOutline,
  ShieldCheckmarkOutline, SwapHorizontalOutline, CloudDownloadOutline,
} from '@vicons/ionicons5'
import { t } from '@/i18n'

export interface NavDef {
  name: string
  label: string
  icon: Component
  /** 固定显示，不能隐藏 */
  fixed?: boolean
  /** 只在电脑的侧栏显示（手机底栏始终不显示） */
  sideOnly?: boolean
}

/** 导航入口（侧栏、底栏共用，按此顺序显示）。Z-Library 需管理员开启，管理只对管理员显示 */
export function navItems(): NavDef[] {
  return [
    { name: 'shelf', label: t('书架'), icon: LibraryOutline, fixed: true },
    { name: 'search', label: t('搜索'), icon: SearchOutline, fixed: true },
    { name: 'explore', label: t('发现'), icon: CompassOutline },
    { name: 'sources', label: t('书源'), icon: CodeSlashOutline, fixed: true },
    { name: 'rules', label: t('规则'), icon: SwapHorizontalOutline },
    { name: 'zlibrary', label: 'Z-Library', icon: CloudDownloadOutline },
    { name: 'settings', label: t('我的'), icon: PersonCircleOutline, fixed: true },
    { name: 'admin', label: t('管理'), icon: ShieldCheckmarkOutline, sideOnly: true },
  ]
}

/** 侧栏、底栏中隐藏的入口（记隐藏的，新增的入口默认显示） */
export interface NavLayout {
  side: string[]
  bottom: string[]
}

/** 默认：侧栏全部显示；底栏不显示 Z-Library（底栏空间小，与原来一致） */
export const DEFAULT_NAV_LAYOUT: NavLayout = { side: [], bottom: ['zlibrary'] }

export function normalizeNavLayout(v: unknown): NavLayout | null {
  const o = v as Partial<NavLayout> | null
  if (!o || !Array.isArray(o.side) || !Array.isArray(o.bottom)) return null
  const str = (a: unknown[]) => a.filter((x): x is string => typeof x === 'string')
  return { side: str(o.side), bottom: str(o.bottom) }
}
