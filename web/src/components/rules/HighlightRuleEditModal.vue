<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NAutoComplete, NButton, NCheckbox, NForm, NFormItem, NInput, NModal, NSpace, NSwitch, useMessage } from 'naive-ui'
import { useHighlightStore } from '@/stores/highlight'
import {
  createHighlighter, highlightRuleError, isEmptyStyle, parseStyle, renumber, stringifyStyle, type HighlightRule, type HighlightStyle,
} from '@/utils/highlight'
import HighlightStyleEditor from './HighlightStyleEditor.vue'
import { t } from '@/i18n'
import { useBusy } from '@/composables/useBusy'

/**
 * 新建 / 编辑高亮规则（规则页，以及阅读页从标注“创建规则”）。
 * isNew 时保存为新规则，放在列表最后（叠加时优先级最高）；否则按 uuid 替换原规则
 */
const rule = defineModel<HighlightRule | null>('rule', { required: true })
const props = defineProps<{ isNew: boolean }>()
const emit = defineEmits<{ saved: [] }>()

const store = useHighlightStore()
const busy = useBusy()
const message = useMessage()

const editing = ref<HighlightRule | null>(null)
const editStyle = ref<HighlightStyle>({})
const previewText = ref('')
watch(
  rule,
  (r) => {
    editing.value = r ? { ...r } : null
    editStyle.value = parseStyle(r?.style)
    previewText.value = ''
  },
  { immediate: true },
)

const groups = computed(() => [...new Set(store.rules.map((r) => r.group).filter((g): g is string => !!g))].sort((a, b) => a.localeCompare(b)))

function errorText(r: Pick<HighlightRule, 'pattern' | 'isRegex'>): string | null {
  const err = highlightRuleError(r)
  if (err === 'empty') return t('关键词不能为空')
  if (err === 'matchesEmpty') return t('这个正则能匹配空字符串，请修改')
  return err ? t('规则无效：{0}', [err]) : null
}

const editError = computed(() => (editing.value?.pattern ? errorText(editing.value) : null))

/** 预览：可以输入一段文字看效果 */
const previewParts = computed(() => {
  const r = editing.value
  const text = previewText.value || r?.pattern || t('示例文字')
  if (!r || editError.value || isEmptyStyle(editStyle.value)) return [{ text }]
  const one = { ...r, style: stringifyStyle(editStyle.value), isEnabled: true, scope: null, applyToBody: true }
  return createHighlighter([one], null)(text) ?? [{ text }]
})

async function save() {
  const r = editing.value
  if (!r) return
  const err = errorText(r)
  if (err) return message.error(err)
  if (isEmptyStyle(editStyle.value)) return message.error(t('请至少设置一种样式'))
  if (!r.applyToBody && !r.applyToTitle) return message.error(t('请至少选择正文或章节标题中的一项'))
  const saved: HighlightRule = { ...r, group: r.group?.trim() || null, scope: r.scope?.trim() || null, style: stringifyStyle(editStyle.value) }
  const ordered = renumber(store.rules)
  const list = props.isNew
    ? [...ordered, { ...saved, order: ordered.length }]
    : ordered.map((x) => (x.uuid === saved.uuid ? saved : x))
  try {
    await store.save(renumber(list))
    rule.value = null
    message.success(t('已保存'))
    emit('saved')
  } catch (e: any) {
    message.error(e.message)
  }
}
</script>

<template>
  <n-modal :show="!!rule" preset="card" :title="isNew ? t('新建高亮规则') : t('编辑高亮规则')" style="max-width: 560px"
    @update:show="(v: boolean) => !v && (rule = null)">
    <n-form v-if="editing" label-placement="left" label-width="90">
      <n-form-item :label="t('名称')"><n-input v-model:value="editing.name" /></n-form-item>
      <n-form-item :label="t('分组')">
        <n-auto-complete :value="editing.group ?? ''" :options="groups.filter((g) => g.includes(editing!.group ?? ''))" :placeholder="t('分组（可选）')"
          clearable @update:value="(v: string | null) => (editing!.group = v || null)" />
      </n-form-item>
      <n-form-item :label="t('关键词 / 正则')" :validation-status="editError ? 'error' : undefined" :feedback="editError ?? undefined">
        <n-input v-model:value="editing.pattern" type="textarea" :autosize="{ minRows: 1 }" class="mono" :placeholder="t('要高亮的文字，如人名')" />
      </n-form-item>
      <n-form-item :label="t('使用正则')"><n-switch v-model:value="editing.isRegex" /></n-form-item>
      <n-form-item :label="t('作用于')">
        <n-space>
          <n-checkbox v-model:checked="editing.applyToBody">{{ t('正文') }}</n-checkbox>
          <n-checkbox v-model:checked="editing.applyToTitle">{{ t('章节标题') }}</n-checkbox>
        </n-space>
      </n-form-item>
      <n-form-item :label="t('作用范围')">
        <n-input :value="editing.scope ?? ''" :placeholder="t('书名或书源地址，留空为全部')" @update:value="(v: string) => (editing!.scope = v || null)" />
      </n-form-item>
      <n-form-item :label="t('样式')">
        <highlight-style-editor v-model="editStyle" />
      </n-form-item>
      <n-form-item :label="t('预览')">
        <div class="preview-box">
          <n-input v-model:value="previewText" size="small" :placeholder="t('输入一段文字试试效果')" />
          <p class="preview"><template v-for="(p, i) in previewParts" :key="i"><span v-if="p.style" class="hl-sample" :style="p.style">{{ p.text }}</span><template v-else>{{ p.text }}</template></template></p>
        </div>
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="end"><n-button type="primary" :loading="busy.is('save')" @click="busy.run('save', save)">{{ t('保存') }}</n-button></n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.preview-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preview {
  margin: 0;
  min-height: 1.8em;
  font-size: 16px;
  line-height: 1.9;
  word-break: break-word;
}

.hl-sample {
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
</style>
