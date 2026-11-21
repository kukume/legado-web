import { defineStore } from 'pinia'
import { ref } from 'vue'
import { prefsApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { normalizeHighlightRules, type HighlightRule } from '@/utils/highlight'

const PREF_KEY = 'highlightRules'

/**
 * 高亮规则（见 utils/highlight）：保存在账号的偏好里，换设备也一样；
 * 本机按用户缓存一份，离线阅读时也能高亮
 */
export const useHighlightStore = defineStore('highlight', () => {
  const rules = ref<HighlightRule[]>([])
  let loadedFor: number | null = null

  const cacheKey = () => `legado_highlight_rules_${useAuthStore().user?.id ?? 0}`

  function readCache(): HighlightRule[] {
    try {
      return normalizeHighlightRules(JSON.parse(localStorage.getItem(cacheKey()) || '[]'))
    } catch {
      return []
    }
  }

  function writeCache(list: HighlightRule[]) {
    try {
      localStorage.setItem(cacheKey(), JSON.stringify(list))
    } catch {
      /* ignore */
    }
  }

  /** 读取规则：先用本机缓存，再以服务器为准（force 时总是重新读取） */
  async function load(force = false) {
    const uid = useAuthStore().user?.id ?? 0
    if (!force && loadedFor === uid) return
    if (loadedFor !== uid) rules.value = readCache()
    loadedFor = uid
    try {
      const remote = await prefsApi.get<unknown>(PREF_KEY)
      if (remote !== null) {
        rules.value = normalizeHighlightRules(remote)
        writeCache(rules.value)
      }
    } catch (e) {
      // 离线时用缓存；管理页面需要知道失败
      if (force) throw e
    }
  }

  /** 保存全部规则（失败时恢复原来的列表并抛出错误） */
  async function save(list: HighlightRule[]) {
    const prev = rules.value
    rules.value = list
    try {
      await prefsApi.set(PREF_KEY, list)
      writeCache(list)
    } catch (e) {
      rules.value = prev
      throw e
    }
  }

  return { rules, load, save }
})
