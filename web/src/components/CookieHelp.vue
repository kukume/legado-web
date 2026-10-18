<script setup lang="ts">
/** 获取网站 Cookie 的说明（网页版无法读取浏览器里其他网站的 Cookie，需要用户手动复制） */
import { NCollapse, NCollapseItem } from 'naive-ui'
import { t } from '@/i18n'

defineProps<{ site?: string }>()
</script>

<template>
  <n-collapse class="cookie-help">
    <n-collapse-item name="help" :title="t('如何获取 Cookie？')">
      <ol>
        <li>{{ t('在电脑浏览器中打开{0}并登录；', [site ? ` ${site} ` : t('该网站')]) }}</li>
        <li>{{ t('按 F12 打开开发者工具，切到“网络 / Network”，刷新页面，点第一个请求；') }}</li>
        <li>{{ t('在“请求标头 / Request Headers”里找到') }} <code>Cookie</code>{{ t('，复制它的整段值粘贴到这里。') }}</li>
      </ol>
      <p>{{ t('也可以在“应用 / Application → Cookie”里逐个复制，按') }} <code>{{ t('名称=值; 名称2=值2') }}</code> {{ t('的格式填写。Cookie 相当于登录凭证，请不要发给别人。') }}</p>
    </n-collapse-item>
  </n-collapse>
</template>

<style scoped>
.cookie-help {
  margin-top: 8px;
  font-size: 12px;
}

.cookie-help ol {
  margin: 0 0 6px;
  padding-left: 18px;
  line-height: 1.7;
}

.cookie-help p {
  margin: 0;
  line-height: 1.6;
}
</style>
