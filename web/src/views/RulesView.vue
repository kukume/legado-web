<script setup lang="ts">
import { onActivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTab, NTabs } from 'naive-ui'
import ReplaceRulesPanel from '@/components/rules/ReplaceRulesPanel.vue'
import DictRulesPanel from '@/components/rules/DictRulesPanel.vue'
import HighlightRulesPanel from '@/components/rules/HighlightRulesPanel.vue'
import TxtTocRulesPanel from '@/components/rules/TxtTocRulesPanel.vue'
import { t } from '@/i18n'

/** 规则：替换规则、高亮规则、字典规则、TXT 目录规则（当前标签记在地址的 ?tab= 里） */
const route = useRoute()
const router = useRouter()
const tabs = ['replace', 'highlight', 'dict', 'txtToc'] as const
type Tab = (typeof tabs)[number]
const tabOf = (q: unknown): Tab => ((tabs as readonly string[]).includes(String(q)) ? (q as Tab) : 'replace')
const tab = ref<Tab>(tabOf(route.query.tab))
// 页面有缓存：离开后路由变成别的页面，只在规则页时跟随地址
watch(
  () => route.query.tab,
  (q) => {
    if (route.name === 'rules') tab.value = tabOf(q)
  },
)
function setTab(t: Tab) {
  router.replace({ query: { ...route.query, tab: t === 'replace' ? undefined : t } })
}
// 从侧栏回到规则页（地址没有 ?tab=）时回到上次的标签
onActivated(() => {
  if (route.query.tab === undefined && tab.value !== 'replace') setTab(tab.value)
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>{{ t('规则') }}</h2>
    </div>
    <n-tabs :value="tab" type="line" style="margin: -8px 0 12px" @update:value="setTab">
      <n-tab name="replace">{{ t('替换规则') }}</n-tab>
      <n-tab name="highlight">{{ t('高亮规则') }}</n-tab>
      <n-tab name="dict">{{ t('字典规则') }}</n-tab>
      <n-tab name="txtToc">{{ t('TXT 目录规则') }}</n-tab>
    </n-tabs>
    <replace-rules-panel v-if="tab === 'replace'" />
    <highlight-rules-panel v-else-if="tab === 'highlight'" />
    <dict-rules-panel v-else-if="tab === 'dict'" />
    <txt-toc-rules-panel v-else />
  </div>
</template>
