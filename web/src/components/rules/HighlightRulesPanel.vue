<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns, useSelectAll } from '@/utils/mobileTable'
import { computed, h, onMounted, ref } from 'vue'
import { useReactivated } from '@/composables/useReactivated'
import {
  NButton, NCheckbox, NDataTable, NInput, NModal, NSelect, NSpace, NSwitch, NTag,
  useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import { storeToRefs } from 'pinia'
import { useHighlightStore } from '@/stores/highlight'
import {
  blankHighlightRule, exportJson, highlightRuleError, mergeImported, normalizeHighlightRules, parseStyle, renumber, styleToCss,
  type HighlightRule,
} from '@/utils/highlight'
import HighlightRuleEditModal from './HighlightRuleEditModal.vue'
import { downloadText } from '@/utils/media'
import { t } from '@/i18n'
import { useBusy } from '@/composables/useBusy'

/**
 * 高亮规则（与阅读 App 的高亮规则相同，导出的 JSON 可以互相导入）：阅读时把正文中匹配的文字用指定的样式显示。
 * 规则按列表顺序叠加，排在后面的优先
 */
const busy = useBusy()
const message = useMessage()
const dialog = useDialog()
const store = useHighlightStore()
const { rules } = storeToRefs(store)
const loading = ref(false)
const checked = ref<string[]>([])

const ordered = computed(() => renumber(rules.value))

// ---------------- 分组筛选 ----------------
const NO_GROUP = '\u0000none'
const groupFilter = ref<string | null>(null)
const groups = computed(() => [...new Set(rules.value.map((r) => r.group).filter((g): g is string => !!g))].sort((a, b) => a.localeCompare(b)))
const groupOptions = computed(() => [
  ...groups.value.map((g) => ({ label: g, value: g })),
  ...(rules.value.some((r) => !r.group) ? [{ label: t('未分组'), value: NO_GROUP }] : []),
])
const shown = computed(() => {
  const g = groupFilter.value
  if (!g) return ordered.value
  return ordered.value.filter((r) => (g === NO_GROUP ? !r.group : r.group === g))
})

async function load(quiet = false) {
  if (!quiet) loading.value = true
  try {
    await store.load(true)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
// 规则页有缓存：回到页面时静默刷新（在阅读页等其它地方可能改过规则）
useReactivated(() => load(true))

async function saveAll(list: HighlightRule[]) {
  try {
    await store.save(renumber(list))
    return true
  } catch (e: any) {
    message.error(e.message)
    return false
  }
}

// ---------------- 编辑 ----------------
const editing = ref<HighlightRule | null>(null)
const isNew = ref(false)
function edit(r: HighlightRule | null) {
  isNew.value = !r
  editing.value = r ? { ...r } : { ...blankHighlightRule(), group: groupFilter.value && groupFilter.value !== NO_GROUP ? groupFilter.value : null }
}

function toggle(r: HighlightRule, v: boolean) {
  saveAll(rules.value.map((x) => (x.uuid === r.uuid ? { ...x, isEnabled: v } : x)))
}

/** 把选中的规则移到最前（优先级最低）或最后（优先级最高），与 App 相同 */
function move(ids: string[], toTop: boolean) {
  const sel = new Set(ids)
  const moving = ordered.value.filter((r) => sel.has(r.uuid))
  const staying = ordered.value.filter((r) => !sel.has(r.uuid))
  const list = (toTop ? [...moving, ...staying] : [...staying, ...moving]).map((r, i) => ({ ...r, order: i }))
  saveAll(list)
}

function remove(ids: string[]) {
  dialog.warning({
    title: t('删除规则'),
    content: t('确定删除 {0} 条规则？', [ids.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      if (await saveAll(rules.value.filter((r) => !ids.includes(r.uuid)))) checked.value = []
    },
  })
}

// ---------------- 导入导出 ----------------
const showImport = ref(false)
const importText = ref('')
async function doImport() {
  let parsed: unknown
  try {
    parsed = JSON.parse(importText.value)
  } catch {
    return message.error(t('JSON 格式错误'))
  }
  const list = normalizeHighlightRules(parsed)
  if (!list.length) return message.error(t('没有可导入的规则'))
  if (await saveAll(mergeImported(rules.value, list))) {
    const bad = list.filter((r) => highlightRuleError(r)).length
    message.success(bad ? t('导入 {0} 条，其中 {1} 条的正则网页版不支持，阅读时不生效', [list.length, bad]) : t('导入 {0} 条', [list.length]))
    showImport.value = false
    importText.value = ''
  }
}

async function onImportFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (f) importText.value = await f.text()
}

function exportRules() {
  const ids = new Set(checked.value)
  downloadText('highlightRule.json', exportJson(ids.size ? ordered.value.filter((r) => ids.has(r.uuid)) : ordered.value))
}

// ---------------- 列表 ----------------
const isMobile = useIsMobile()
const selectAll = useSelectAll(shown, checked, (r: HighlightRule) => r.uuid)

/** 列表里的样式示例：用规则的名称或关键词（太长时截断） */
const label = (r: HighlightRule) => {
  const s = r.name || r.pattern
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}
const sample = (r: HighlightRule) => h('span', { class: 'hl-sample', style: styleToCss(parseStyle(r.style)) }, label(r))
const invalidTag = (r: HighlightRule) =>
  highlightRuleError(r) ? h(NTag, { size: 'tiny', type: 'warning', bordered: false, style: 'margin-left:6px' }, () => t('不生效')) : null
const sub = (r: HighlightRule) =>
  [r.isRegex ? t('正则') : '', r.group ?? '', r.applyToTitle ? (r.applyToBody ? t('正文和标题') : t('仅标题')) : '', r.pattern]
    .filter(Boolean).join(' · ')

const desktopColumns: DataTableColumns<HighlightRule> = [
  { type: 'selection' },
  { title: t('启用'), key: 'isEnabled', width: 70, render: (r) => h(NSwitch, { size: 'small', value: r.isEnabled, onUpdateValue: (v: boolean) => toggle(r, v) }) },
  { title: t('效果'), key: 'name', render: (r) => [sample(r), invalidTag(r)] },
  {
    title: t('规则'),
    key: 'pattern',
    ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono' }, [r.isRegex ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-right:4px' }, () => t('正则')) : null, r.pattern]),
  },
  { title: t('分组'), key: 'group', width: 110, ellipsis: { tooltip: true }, render: (r) => r.group || '-' },
  { title: t('范围'), key: 'scope', width: 140, ellipsis: { tooltip: true }, render: (r) => r.scope || t('全部') },
  {
    title: t('操作'),
    key: 'op',
    width: 120,
    render: (r) => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'tiny', onClick: () => edit(r) }, () => t('编辑')),
      h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove([r.uuid]) }, () => t('删除')),
    ]),
  },
]

const columns = computed(() =>
  isMobile.value
    ? mobileColumns<HighlightRule>({
        name: (r) => [sample(r), invalidTag(r)],
        sub,
        enabled: (r) => r.isEnabled,
        onToggle: toggle,
        actions: (r) => [
          { key: 'edit', label: t('编辑'), onSelect: () => edit(r) },
          { key: 'top', label: t('移到最前'), onSelect: () => move([r.uuid], true) },
          { key: 'bottom', label: t('移到最后'), onSelect: () => move([r.uuid], false) },
          { key: 'delete', label: t('删除'), danger: true, onSelect: () => remove([r.uuid]) },
        ],
      })
    : desktopColumns,
)
</script>

<template>
  <div>
    <div class="rule-toolbar">
      <n-checkbox v-if="isMobile" class="select-all" :checked="selectAll.all.value" :indeterminate="selectAll.some.value" @update:checked="selectAll.toggleAll">{{ t('全选') }}</n-checkbox>
      <n-select v-if="groups.length" v-model:value="groupFilter" size="small" :options="groupOptions" :placeholder="t('全部分组')" clearable style="width: 130px" />
      <n-button size="small" :disabled="!checked.length" type="error" ghost @click="remove(checked)">{{ t('删除选中') }}</n-button>
      <n-button size="small" :disabled="!checked.length" @click="move(checked, true)">{{ t('移到最前') }}</n-button>
      <n-button size="small" :disabled="!checked.length" @click="move(checked, false)">{{ t('移到最后') }}</n-button>
      <n-button size="small" :disabled="!rules.length" @click="exportRules">{{ checked.length ? t('导出选中') : t('导出') }}</n-button>
      <n-button size="small" @click="showImport = true">{{ t('导入') }}</n-button>
      <n-button size="small" type="primary" @click="edit(null)">{{ t('新建') }}</n-button>
    </div>
    <n-data-table
      v-model:checked-row-keys="checked"
      :columns="columns"
      :data="shown"
      :row-key="(r: HighlightRule) => r.uuid"
      :loading="loading"
      size="small"
      :class="{ 'm-table': isMobile }"
      :scroll-x="isMobile ? undefined : 760"
      :table-layout="isMobile ? 'fixed' : 'auto'"
    />

    <highlight-rule-edit-modal v-model:rule="editing" :is-new="isNew" />

    <n-modal v-model:show="showImport" preset="card" :title="t('导入高亮规则')" style="max-width: 560px">
      <n-input v-model:value="importText" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" :placeholder="t('粘贴阅读 App 导出的高亮规则 JSON')" class="mono" />
      <template #footer>
        <n-space justify="space-between" align="center">
          <label class="file-pick">
            <input type="file" accept=".json,application/json,text/plain" hidden @change="onImportFile" />
            <n-button size="small" tag="span">{{ t('选择文件') }}</n-button>
          </label>
          <n-button type="primary" :loading="busy.is('doImport')" @click="busy.run('doImport', doImport)">{{ t('导入') }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
:deep(.hl-sample) {
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}

.file-pick {
  cursor: pointer;
}
</style>
