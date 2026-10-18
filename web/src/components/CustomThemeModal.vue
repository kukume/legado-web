<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NColorPicker, NForm, NFormItem, NInput, NModal, NSpace, useDialog, useMessage } from 'naive-ui'
import { prefsApi } from '@/api'
import { customToTheme, type CustomTheme } from '@/stores/reader'
import { parseHex } from '@/utils/color'
import { t } from '@/i18n'

/** 新建 / 编辑自定义阅读主题（只有颜色） */
const props = defineProps<{ show: boolean; theme: CustomTheme | null }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [CustomTheme]; deleted: [string] }>()
const message = useMessage()
const dialog = useDialog()

const name = ref('')
const bg = ref('#f3ead7')
const text = ref('#3b3226')
const autoPanel = ref(true)
const panel = ref('#fbf6ea')
const autoDesk = ref(true)
const desk = ref('#e4d9c1')

watch(
  () => props.show,
  (v) => {
    if (!v) return
    const theme = props.theme
    name.value = theme?.name ?? t('我的主题')
    bg.value = theme?.bg ?? '#f3ead7'
    text.value = theme?.text ?? '#3b3226'
    autoPanel.value = !theme?.panel
    autoDesk.value = !theme?.desk
    const derived = customToTheme({ id: '', name: '', bg: bg.value, text: text.value })
    panel.value = theme?.panel || derived.panel
    desk.value = theme?.desk || derived.desk
  },
)

// 背景色变了而面板色、底色是自动时，预览跟着变
const preview = computed(() => customToTheme({
  id: '', name: '', bg: bg.value, text: text.value,
  panel: autoPanel.value ? undefined : panel.value,
  desk: autoDesk.value ? undefined : desk.value,
}))

/** 早期版本支持过背景图片（存在用户偏好 readerBg_{id} 中），保存或删除主题时顺便清掉 */
function clearOldImage(t: CustomTheme | null) {
  if (t?.image) prefsApi.set(`readerBg_${t.id}`, '').catch(() => {})
}

function save() {
  if (!parseHex(bg.value) || !parseHex(text.value)) return message.warning(t('请填写有效的颜色（如 #f3ead7）'))
  clearOldImage(props.theme)
  emit('saved', {
    id: props.theme?.id ?? Date.now().toString(36),
    name: name.value.trim() || t('我的主题'),
    bg: bg.value,
    text: text.value,
    panel: autoPanel.value ? undefined : panel.value,
    desk: autoDesk.value ? undefined : desk.value,
  })
  emit('update:show', false)
}

function remove() {
  const theme = props.theme
  if (!theme) return
  dialog.warning({
    title: t('删除主题'),
    content: t('确定删除「{0}」？', [theme.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: () => {
      clearOldImage(theme)
      emit('deleted', theme.id)
      emit('update:show', false)
    },
  })
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="theme ? t('编辑主题') : t('自定义主题')"
    style="width: 460px; max-width: 94vw"
    :auto-focus="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="preview" :style="{ background: preview.bg, color: preview.text }">
      <div class="preview-title">{{ t('第一章 预览') }}</div>
      <div>　　{{ t('这是正文的预览，可以看看文字在这个背景上是否清楚。') }}</div>
    </div>
    <n-form label-placement="left" label-width="84" size="small" style="margin-top: 12px">
      <n-form-item :label="t('名称')">
        <n-input v-model:value="name" maxlength="12" />
      </n-form-item>
      <n-form-item :label="t('背景色')">
        <n-color-picker v-model:value="bg" :modes="['hex']" :show-alpha="false" />
      </n-form-item>
      <n-form-item :label="t('文字色')">
        <n-color-picker v-model:value="text" :modes="['hex']" :show-alpha="false" />
      </n-form-item>
      <n-form-item :label="t('菜单面板')">
        <n-space align="center" :wrap="false" style="width: 100%">
          <n-checkbox v-model:checked="autoPanel">{{ t('自动') }}</n-checkbox>
          <n-color-picker v-if="!autoPanel" v-model:value="panel" :modes="['hex']" :show-alpha="false" style="width: 180px" />
        </n-space>
      </n-form-item>
      <n-form-item :label="t('外部底色')">
        <n-space align="center" :wrap="false" style="width: 100%">
          <n-checkbox v-model:checked="autoDesk">{{ t('自动') }}</n-checkbox>
          <n-color-picker v-if="!autoDesk" v-model:value="desk" :modes="['hex']" :show-alpha="false" style="width: 180px" />
        </n-space>
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="space-between">
        <n-button v-if="theme" type="error" ghost @click="remove">{{ t('删除') }}</n-button>
        <span v-else />
        <n-space>
          <n-button @click="emit('update:show', false)">{{ t('取消') }}</n-button>
          <n-button type="primary" @click="save">{{ t('保存') }}</n-button>
        </n-space>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.preview {
  padding: 14px 18px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.25);
  font-size: 15px;
  line-height: 1.8;
}

.preview-title {
  margin-bottom: 4px;
  font-size: 17px;
  font-weight: 600;
}
</style>
