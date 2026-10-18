<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTab, NTabs } from 'naive-ui'
import ReplaceRulesPanel from '@/components/rules/ReplaceRulesPanel.vue'
import DictRulesPanel from '@/components/rules/DictRulesPanel.vue'
import TxtTocRulesPanel from '@/components/rules/TxtTocRulesPanel.vue'
import { t } from '@/i18n'

/** 规则：替换规则、字典规则、TXT 目录规则（当前标签记在地址的 ?tab= 里） */
const route = useRoute()
const router = useRouter()
const tabs = ['replace', 'dict', 'txtToc'] as const
type Tab = (typeof tabs)[number]
const tab = computed<Tab>(() => (tabs as readonly string[]).includes(String(route.query.tab)) ? (route.query.tab as Tab) : 'replace')
function setTab(t: Tab) {
  router.replace({ query: { ...route.query, tab: t === 'replace' ? undefined : t } })
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>{{ t('规则') }}</h2>
    </div>
    <n-tabs :value="tab" type="line" style="margin: -8px 0 12px" @update:value="setTab">
      <n-tab name="replace">{{ t('替换规则') }}</n-tab>
      <n-tab name="dict">{{ t('字典规则') }}</n-tab>
      <n-tab name="txtToc">{{ t('TXT 目录规则') }}</n-tab>
    </n-tabs>
    <replace-rules-panel v-if="tab === 'replace'" />
    <dict-rules-panel v-else-if="tab === 'dict'" />
    <txt-toc-rules-panel v-else />
  </div>
</template>
