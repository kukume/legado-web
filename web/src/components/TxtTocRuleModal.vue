<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NRadio, NSpin, NTag, useMessage } from 'naive-ui'
import { shelfApi, txtTocApi } from '@/api'
import { TXT_TOC_SEPARATOR, type Book, type TxtTocRule } from '@/api/types'
import { t } from '@/i18n'

/**
 * 本地 TXT 的目录规则（App 目录页的“TXT 目录规则”对话框）：为这本书指定分章规则，只对这本书生效。
 * 选中的规则保存在 book.tocUrl（规则 + 分隔符 + js 处理），与 App 相同。
 */
const props = defineProps<{ book: Book }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ applied: [book: Book] }>()
const message = useMessage()

const rules = ref<TxtTocRule[]>([])
const loading = ref(false)
const applying = ref(false)
/** 选中的规则 id；0 表示自动识别 */
const selected = ref(0)

const tocUrlOf = (r: TxtTocRule) => r.rule + TXT_TOC_SEPARATOR + r.replacement
/** 当前书籍使用的规则（自动识别出来的也算） */
const currentId = computed(() => {
  const url = props.book.tocUrl
  if (!url) return null
  return (rules.value.find((r) => tocUrlOf(r) === url) ?? rules.value.find((r) => url.split(TXT_TOC_SEPARATOR)[0] === r.rule))?.id ?? null
})

watch(show, async (v) => {
  if (!v) return
  loading.value = true
  try {
    rules.value = (await txtTocApi.list()).rules
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
  selected.value = currentId.value ?? 0
})

async function apply() {
  const r = rules.value.find((x) => x.id === selected.value)
  applying.value = true
  try {
    const nb = await shelfApi.setTxtToc(props.book.id, r ? tocUrlOf(r) : '')
    message.success(t('目录已按新规则重新解析'))
    emit('applied', nb)
    show.value = false
  } catch (e: any) {
    message.error(e.message)
  } finally {
    applying.value = false
  }
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="t('TXT 目录规则')" style="width: 520px; max-width: 94vw" :auto-focus="false">
    <p class="muted small" style="margin: 0 0 10px">{{ t('只对这本书生效。可以在“规则 → TXT 目录规则”中修改或新建规则。') }}</p>
    <div v-if="loading" class="toc-center"><n-spin size="small" /></div>
    <n-empty v-else-if="!rules.length" :description="t('没有目录规则')" />
    <div v-else class="toc-list">
      <label class="toc-item" :class="{ on: selected === 0 }">
        <n-radio :checked="selected === 0" @update:checked="selected = 0" />
        <div class="toc-text">
          <div>{{ t('自动识别') }}</div>
          <div class="muted small">{{ t('按序号尝试所有启用的规则，选出最合适的') }}</div>
        </div>
      </label>
      <label v-for="r in rules" :key="r.id" class="toc-item" :class="{ on: selected === r.id }">
        <n-radio :checked="selected === r.id" @update:checked="selected = r.id" />
        <div class="toc-text">
          <div>
            {{ r.name }}
            <n-tag v-if="r.id === currentId" size="tiny" type="success" :bordered="false">{{ t('当前') }}</n-tag>
            <n-tag v-if="!r.enable" size="tiny" :bordered="false">{{ t('未启用') }}</n-tag>
          </div>
          <div v-if="r.example" class="muted small ellipsis">{{ r.example.replace(/\n/g, ' / ') }}</div>
        </div>
      </label>
    </div>
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button @click="show = false">{{ t('取消') }}</n-button>
        <n-button type="primary" :loading="applying" :disabled="loading" @click="apply">{{ t('重新分章') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.toc-list {
  max-height: min(56vh, 480px);
  overflow-y: auto;
  margin: 0 -4px;
}

.toc-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 6px;
  cursor: pointer;
}

.toc-item.on {
  background: color-mix(in srgb, var(--app-accent) 10%, transparent);
}

.toc-item :deep(.n-radio) {
  margin-top: 2px;
}

.toc-text {
  min-width: 0;
  flex: 1;
}

.toc-center {
  display: flex;
  justify-content: center;
  padding: 24px 0;
}
</style>
