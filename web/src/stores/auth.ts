import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api'
import { getToken, NetworkError, setToken } from '@/api/http'
import type { Me, PublicConfig } from '@/api/types'
import { t } from '@/i18n'

const ME_KEY = 'legado_me'

function loadCachedMe(): Me | null {
  try {
    const s = localStorage.getItem(ME_KEY)
    return s ? (JSON.parse(s) as Me) : null
  } catch {
    return null
  }
}

function saveCachedMe(me: Me | null) {
  try {
    if (me) localStorage.setItem(ME_KEY, JSON.stringify(me))
    else localStorage.removeItem(ME_KEY)
  } catch {
    /* ignore */
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const me = ref<Me | null>(null)
  const config = ref<PublicConfig | null>(null)

  const user = computed(() => me.value?.user ?? null)
  const isAdmin = computed(() => me.value?.user.role === 'admin')
  const loggedIn = computed(() => !!token.value)
  /** 站点名称：管理员没有改过（仍是默认的“阅读 Web”）时按界面语言显示 */
  const siteName = computed(() => {
    const n = config.value?.siteName?.trim()
    return n && n !== '阅读 Web' ? n : t('阅读 Web')
  })

  async function loadConfig() {
    config.value = await authApi.config()
    document.title = siteName.value
    return config.value
  }

  /**
   * 获取当前用户。离线时使用上次缓存的用户信息（离线阅读需要知道是哪个用户的数据）
   */
  async function fetchMe() {
    if (!token.value) return null
    try {
      me.value = await authApi.me()
      saveCachedMe(me.value)
    } catch (e) {
      const cached = e instanceof NetworkError ? loadCachedMe() : null
      if (!cached) throw e
      me.value = cached
    }
    return me.value
  }

  function applyToken(t: string) {
    token.value = t
    setToken(t)
  }

  async function login(username: string, password: string, captchaToken?: string) {
    const r = await authApi.login(username, password, captchaToken)
    applyToken(r.token)
    await fetchMe()
  }

  async function register(username: string, password: string, inviteCode?: string, captchaToken?: string) {
    const r = await authApi.register(username, password, inviteCode, captchaToken)
    applyToken(r.token)
    await fetchMe()
  }

  function logout() {
    applyToken('')
    me.value = null
    saveCachedMe(null)
  }

  return { token, me, user, isAdmin, loggedIn, config, siteName, loadConfig, fetchMe, login, register, logout, applyToken }
})
