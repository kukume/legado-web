<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NForm, NFormItem, NInput, NModal, NRadioButton, NRadioGroup, NSpace, useMessage } from 'naive-ui'
import { replaceApi } from '@/api'
import { t } from '@/i18n'

/** 阅读页选中文字后快速添加替换规则 */
const props = defineProps<{
  show: boolean
  /** 选中的文字（作为替换的内容） */
  text: string
  bookName: string
  /** 书源地址（替换范围为“本书源”时使用） */
  origin: string
  originName: string
}>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>()
const message = useMessage()

const name = ref('')
const pattern = ref('')
const replacement = ref('')
const isRegex = ref(false)
const scope = ref<'book' | 'source' | 'all'>('book')
const saving = ref(false)

watch(
  () => props.show,
  (v) => {
    if (!v) return
    pattern.value = props.text
    name.value = props.text.replace(/\s+/g, ' ').slice(0, 20)
    replacement.value = ''
    isRegex.value = false
    scope.value = 'book'
  },
)

const multiLine = computed(() => pattern.value.includes('\n'))

async function save() {
  if (!pattern.value) return message.warning(t('替换的内容不能为空'))
  if (isRegex.value) {
    try {
      new RegExp(pattern.value)
    } catch (e: any) {
      return message.error(t('正则表达式有误：{0}', [e.message]))
    }
  }
  saving.value = true
  try {
    await replaceApi.save({
      id: 0,
      name: name.value || pattern.value.slice(0, 20),
      group: null,
      pattern: pattern.value,
      replacement: replacement.value,
      scope: scope.value === 'book' ? props.bookName : scope.value === 'source' ? props.origin : null,
      scopeTitle: false,
      scopeContent: true,
      excludeScope: null,
      isEnabled: true,
      isRegex: isRegex.value,
      order: 0,
    })
    message.success(t('已添加替换规则'))
    emit('update:show', false)
    emit('saved')
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="t('添加替换规则')"
    style="width: 480px; max-width: 94vw"
    :auto-focus="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-form label-placement="top" size="small">
      <n-form-item :label="t('替换')">
        <n-input v-model:value="pattern" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }" />
      </n-form-item>
      <div v-if="multiLine" class="hint">{{ t('选中的文字跨了段落，可能匹配不到原文，建议只保留一段') }}</div>
      <n-form-item :label="t('替换为（留空即删除）')">
        <n-input v-model:value="replacement" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }" :placeholder="t('留空表示删除这段文字')" />
      </n-form-item>
      <n-form-item :label="t('范围')">
        <n-radio-group v-model:value="scope" size="small">
          <n-radio-button value="book">{{ t('仅本书') }}</n-radio-button>
          <n-radio-button value="source" :disabled="!origin">{{ t('本书源') }}</n-radio-button>
          <n-radio-button value="all">{{ t('所有书') }}</n-radio-button>
        </n-radio-group>
      </n-form-item>
      <n-form-item :label="t('规则名称')">
        <n-input v-model:value="name" />
      </n-form-item>
      <n-checkbox v-model:checked="isRegex">{{ t('使用正则表达式') }}</n-checkbox>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('update:show', false)">{{ t('取消') }}</n-button>
        <n-button type="primary" :loading="saving" @click="save">{{ t('保存') }}</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.hint {
  margin: -12px 0 12px;
  font-size: 12px;
  color: #d97706;
}
</style>
