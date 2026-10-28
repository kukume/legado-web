<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NInput, NModal, NProgress, NTag, useMessage } from 'naive-ui'
import { sourceApi, type SourceCheckResult } from '@/api'
import { t } from '@/i18n'

/**
 * 书源校验（与 App 相同）：依次检查搜索、详情、目录、正文。
 * 失败的书源加上“搜索失效”“目录失效”等分组，可以按分组筛选后批量禁用或删除；成功的去掉这些分组
 */
const props = defineProps<{ ids: number[] }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ done: [] }>()
const message = useMessage()

const keyword = ref('')
const running = ref(false)
const results = ref<SourceCheckResult[]>([])
const progress = ref({ done: 0, total: 0 })
let controller: AbortController | null = null

const failed = computed(() => results.value.filter((r) => !r.ok))
const passed = computed(() => results.value.filter((r) => r.ok))
/** 失败的排在前面 */
const sorted = computed(() => [...failed.value, ...passed.value])

watch(show, (v) => {
  if (v) {
    results.value = []
    progress.value = { done: 0, total: props.ids.length }
  } else stop()
})

async function start() {
  controller?.abort()
  controller = new AbortController()
  results.value = []
  progress.value = { done: 0, total: props.ids.length }
  running.value = true
  try {
    await sourceApi.check(
      props.ids,
      keyword.value.trim(),
      (e) => {
        if (e.type !== 'result') return
        results.value.push(e.result)
        progress.value = { done: e.done, total: e.total }
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e.name !== 'AbortError') message.error(e.message)
  } finally {
    running.value = false
    controller = null
    emit('done')
  }
}

function stop() {
  controller?.abort()
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="t('校验书源')" style="width: 640px; max-width: 94vw" :mask-closable="!running">
    <p class="muted small" style="margin-top: 0">
      {{ t('依次检查搜索、详情、目录、正文。失败的书源会加上“搜索失效”“目录失效”等分组，可以在分组里筛选出来批量禁用或删除；校验通过的会去掉这些分组。') }}
    </p>
    <div class="check-bar">
      <n-input v-model:value="keyword" size="small" :disabled="running" :placeholder="t('校验关键字（留空用书源自带的，没有则用“我的”）')" />
      <n-button v-if="!running" size="small" type="primary" @click="start">{{ results.length ? t('重新校验') : t('开始校验') }}</n-button>
      <n-button v-else size="small" @click="stop">{{ t('停止') }}</n-button>
    </div>
    <template v-if="running || results.length">
      <n-progress
        type="line"
        :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
        :processing="running"
        :show-indicator="false"
        style="margin: 12px 0 6px"
      />
      <div class="summary small">
        {{ t('已校验 {0}/{1}', [progress.done, progress.total]) }} ·
        <span class="ok">{{ t('通过 {0}', [passed.length]) }}</span> ·
        <span class="bad">{{ t('失败 {0}', [failed.length]) }}</span>
      </div>
      <div class="results">
        <div v-for="r in sorted" :key="r.id" class="result">
          <div class="result-main">
            <span class="ellipsis result-name">{{ r.name }}</span>
            <n-tag v-if="r.ok" size="small" type="success" :bordered="false">{{ t('通过') }}</n-tag>
            <n-tag v-else size="small" type="error" :bordered="false">{{ r.group }}</n-tag>
            <span class="muted small result-time">{{ (r.time / 1000).toFixed(1) }}s</span>
          </div>
          <div v-if="r.error" class="muted small result-err">{{ r.error }}</div>
          <div v-if="!r.ok && !r.saved" class="muted small">{{ t('公共书源，未修改分组') }}</div>
        </div>
      </div>
    </template>
    <n-empty v-else :description="t('已选 {0} 个书源', [ids.length])" style="padding: 20px 0" />
  </n-modal>
</template>

<style scoped>
.check-bar {
  display: flex;
  gap: 8px;
}

.summary {
  margin-bottom: 8px;
  color: var(--app-text-2);
}

.summary .ok {
  color: #18a058;
}

.summary .bad {
  color: #d03050;
}

.results {
  max-height: 50vh;
  overflow-y: auto;
  border-top: 1px solid var(--app-border);
}

.result {
  padding: 8px 2px;
  border-bottom: 1px solid var(--app-border);
}

.result-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-name {
  flex: 1;
  min-width: 0;
}

.result-time {
  flex-shrink: 0;
}

.result-err {
  margin-top: 2px;
  word-break: break-all;
}

.small {
  font-size: 12.5px;
}
</style>
