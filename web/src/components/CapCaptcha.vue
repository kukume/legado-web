<script setup lang="ts">
import { onMounted, ref } from 'vue'
import capWasmUrl from '@cap.js/wasm/browser/cap_wasm_bg.wasm?url'
import hashwxWasmUrl from '@cap.js/wasm/browser/hashwx.wasm?url'
import { t } from '@/i18n'

/** Cap 人机验证（https://capjs.js.org）：验证通过后通过 v-model 拿到一次性的 token */
const props = defineProps<{ endpoint: string }>()
const token = defineModel<string>({ default: '' })
const emit = defineEmits<{ error: [message: string] }>()

const el = ref<HTMLElement & { reset?: () => void }>()
const ready = ref(false)

onMounted(async () => {
  // 求解用的 WASM 随网页一起打包，不从 jsDelivr 下载
  window.CAP_CUSTOM_WASM_URL = capWasmUrl
  window.CAP_CUSTOM_HASHWX_URL = hashwxWasmUrl
  // 只有开启了验证码才加载组件代码
  await import('cap-widget')
  ready.value = true
})

function onSolve(e: Event) {
  token.value = (e as CustomEvent<{ token: string }>).detail.token
}
function onError(e: Event) {
  token.value = ''
  const detail = (e as CustomEvent<{ code?: string; message?: string }>).detail
  // Failed to fetch 之类的网络错误多半是 Cap 实例地址不对或服务不可用
  const network = detail?.code === 'network_error' || /fetch/i.test(detail?.message ?? '')
  emit('error', network ? t('无法连接人机验证服务，请稍后重试') : t('人机验证出错：{0}', [detail?.message || t('未知错误')]))
}

/** 组件里的文字跟随界面语言（组件自带的翻译没有繁体，也不跟随界面切换的语言） */
const labels = {
  'data-cap-i18n-initial-state': t('确认你是真人'),
  'data-cap-i18n-verifying-label': t('正在验证…'),
  'data-cap-i18n-solved-label': t('你是真人'),
  'data-cap-i18n-error-label': t('出错了，请重试'),
  'data-cap-i18n-required-label': t('请确认你是真人'),
  'data-cap-i18n-troubleshooting-label': t('故障排除'),
  'data-cap-i18n-group-aria-label': t('人机验证'),
  'data-cap-i18n-verify-aria-label': t('点击确认你是真人'),
  'data-cap-i18n-verifying-aria-label': t('正在验证你是真人，请稍候'),
  'data-cap-i18n-verified-aria-label': t('已确认你是真人，可以继续了'),
  'data-cap-i18n-error-aria-label': t('发生错误，请重试'),
}

/** token 只能用一次：提交失败后重新验证 */
function reset() {
  token.value = ''
  el.value?.reset?.()
}
defineExpose({ reset })
</script>

<template>
  <div class="cap-wrap">
    <cap-widget
      v-if="ready"
      ref="el"
      :data-cap-api-endpoint="props.endpoint"
      v-bind="labels"
      @solve="onSolve"
      @error="onError"
      @reset="token = ''"
    />
  </div>
</template>

<style scoped>
.cap-wrap {
  width: 100%;
  min-height: 58px;
}

cap-widget {
  --cap-widget-width: 100%;
  --cap-background: var(--app-surface);
  --cap-border-color: var(--app-border);
  --cap-color: var(--app-text);
  --cap-checkbox-background: var(--app-bg);
  --cap-spinner-color: var(--app-accent);
  --cap-border-radius: 8px;
  display: block;
}
</style>
