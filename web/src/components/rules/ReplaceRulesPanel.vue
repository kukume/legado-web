<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns, useSelectAll } from '@/utils/mobileTable'
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton, NCheckbox, NDataTable, NForm, NFormItem, NInput, NInputNumber, NModal, NSpace, NSwitch, NTag,
  useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import { replaceApi } from '@/api'
import type { ReplaceRule } from '@/api/types'
import { downloadText } from '@/utils/media'
import { t } from '@/i18n'

const message = useMessage()
const dialog = useDialog()
const rules = ref<ReplaceRule[]>([])
const loading = ref(false)
const checked = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    rules.value = await replaceApi.list()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const blank = (): ReplaceRule => ({
  id: 0, name: '', group: null, pattern: '', replacement: '', scope: null, scopeTitle: false,
  scopeContent: true, excludeScope: null, isEnabled: true, isRegex: true, order: 0,
})

const editing = ref<ReplaceRule | null>(null)

async function save() {
  if (!editing.value) return
  try {
    await replaceApi.save(editing.value)
    editing.value = null
    await load()
    message.success(t('已保存'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function toggle(r: ReplaceRule, v: boolean) {
  await replaceApi.save({ ...r, isEnabled: v })
  r.isEnabled = v
}

function remove(ids: number[]) {
  dialog.warning({
    title: t('删除规则'),
    content: t('确定删除 {0} 条规则？', [ids.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await replaceApi.remove(ids)
      checked.value = []
      await load()
    },
  })
}

const showImport = ref(false)
const importText = ref('')
async function doImport() {
  try {
    const r = await replaceApi.import(importText.value)
    message.success(t('导入 {0} 条', [r.count]))
    showImport.value = false
    importText.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

function exportRules() {
  downloadText('replaceRule.json', JSON.stringify(rules.value.map(({ id, ...r }) => r), null, 2))
}

const isMobile = useIsMobile()
const selectAll = useSelectAll(rules, checked, (r: ReplaceRule) => r.id)

const desktopColumns: DataTableColumns<ReplaceRule> = [
  { type: 'selection' },
  { title: t('启用'), key: 'isEnabled', width: 70, render: (r) => h(NSwitch, { size: 'small', value: r.isEnabled, onUpdateValue: (v: boolean) => toggle(r, v) }) },
  { title: t('名称'), key: 'name', render: (r) => r.name || '-' },
  {
    title: t('规则'),
    key: 'pattern',
    ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono' }, [r.isRegex ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-right:4px' }, () => t('正则')) : null, `${r.pattern} → ${r.replacement || t('(删除)')}`]),
  },
  { title: t('范围'), key: 'scope', width: 140, ellipsis: { tooltip: true }, render: (r) => r.scope || t('全部') },
  {
    title: t('操作'),
    key: 'op',
    width: 120,
    render: (r) => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'tiny', onClick: () => (editing.value = { ...r }) }, () => t('编辑')),
      h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove([r.id]) }, () => t('删除')),
    ]),
  },
]

/** 手机上与 App 一样只显示多选、名称、启用和菜单 */
const columns = computed(() =>
  isMobile.value
    ? mobileColumns<ReplaceRule>({
        name: (r) => r.name || r.pattern,
        sub: (r) => `${r.isRegex ? t('正则') + ' · ' : ''}${r.pattern} → ${r.replacement || t('(删除)')}`,
        enabled: (r) => r.isEnabled,
        onToggle: toggle,
        actions: (r) => [
          { key: 'edit', label: t('编辑'), onSelect: () => (editing.value = { ...r }) },
          { key: 'delete', label: t('删除'), danger: true, onSelect: () => remove([r.id]) },
        ],
      })
    : desktopColumns,
)
</script>

<template>
  <div>
    <div class="rule-toolbar">
      <p class="muted rule-desc">
        {{ t('用于净化正文（去广告、修正错字等），与阅读 App 的替换规则格式兼容。范围可填书名或书源地址，多个用逗号分隔。') }}
      </p>
      <n-checkbox v-if="isMobile" class="select-all" :checked="selectAll.all.value" :indeterminate="selectAll.some.value" @update:checked="selectAll.toggleAll">{{ t('全选') }}</n-checkbox>
      <n-button size="small" :disabled="!checked.length" type="error" ghost @click="remove(checked)">{{ t('删除选中') }}</n-button>
      <n-button size="small" @click="exportRules">{{ t('导出') }}</n-button>
      <n-button size="small" @click="showImport = true">{{ t('导入') }}</n-button>
      <n-button size="small" type="primary" @click="editing = blank()">{{ t('新建') }}</n-button>
    </div>
    <n-data-table
      v-model:checked-row-keys="checked"
      :columns="columns"
      :data="rules"
      :row-key="(r: ReplaceRule) => r.id"
      :loading="loading"
      size="small"
      :class="{ 'm-table': isMobile }"
      :scroll-x="isMobile ? undefined : 640"
      :table-layout="isMobile ? 'fixed' : 'auto'"
    />

    <n-modal :show="!!editing" preset="card" :title="editing?.id ? t('编辑规则') : t('新建规则')" style="max-width: 520px"
      @update:show="(v: boolean) => !v && (editing = null)">
      <n-form v-if="editing" label-placement="left" label-width="90">
        <n-form-item :label="t('名称')"><n-input v-model:value="editing.name" /></n-form-item>
        <n-form-item :label="t('替换内容')"><n-input v-model:value="editing.pattern" type="textarea" :autosize="{ minRows: 1 }" class="mono" /></n-form-item>
        <n-form-item :label="t('替换为')"><n-input v-model:value="editing.replacement" :placeholder="t('留空表示删除')" class="mono" /></n-form-item>
        <n-form-item :label="t('使用正则')"><n-switch v-model:value="editing.isRegex" /></n-form-item>
        <n-form-item :label="t('作用范围')"><n-input :value="editing.scope ?? ''" :placeholder="t('书名或书源地址，留空为全部')" @update:value="(v: string) => (editing!.scope = v || null)" /></n-form-item>
        <n-form-item :label="t('排除范围')"><n-input :value="editing.excludeScope ?? ''" @update:value="(v: string) => (editing!.excludeScope = v || null)" /></n-form-item>
        <n-form-item :label="t('排序')"><n-input-number v-model:value="editing.order" /></n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="save">{{ t('保存') }}</n-button></n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="showImport" preset="card" :title="t('导入替换规则')" style="max-width: 560px">
      <n-input v-model:value="importText" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" :placeholder="t('粘贴替换规则 JSON')" class="mono" />
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="doImport">{{ t('导入') }}</n-button></n-space>
      </template>
    </n-modal>
  </div>
</template>
