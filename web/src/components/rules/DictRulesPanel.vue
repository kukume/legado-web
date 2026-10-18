<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns, useSelectAll } from '@/utils/mobileTable'
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCheckbox, NDataTable, NForm, NFormItem, NInput, NInputNumber, NModal, NSpace, NSwitch,
  useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import { dictApi } from '@/api'
import type { DictRule } from '@/api/types'
import { downloadText } from '@/utils/media'
import DictModal from '@/components/DictModal.vue'
import { t } from '@/i18n'

/** 字典规则：正文选中文字后“字典”使用的规则，与阅读 App 的字典规则格式相同 */
/** 网址规则里代表查询文字的变量 */
const KEY_VAR = '{{key}}'
const message = useMessage()
const dialog = useDialog()
const rules = ref<DictRule[]>([])
const canEdit = ref(false)
const loading = ref(false)
const checked = ref<string[]>([])

async function load() {
  loading.value = true
  try {
    const r = await dictApi.list()
    rules.value = r.rules
    canEdit.value = r.canEdit
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const blank = (): DictRule => ({
  name: '', urlRule: '', showRule: '', enabled: true,
  sortNumber: rules.value.reduce((m, r) => Math.max(m, r.sortNumber), -1) + 1,
})

/** 正在编辑的规则；oldName 为修改前的名称（新建时为空） */
const editing = ref<{ rule: DictRule; oldName: string } | null>(null)
const saving = ref(false)

async function save() {
  if (!editing.value) return
  saving.value = true
  try {
    await dictApi.save(editing.value.rule, editing.value.oldName || undefined)
    editing.value = null
    await load()
    message.success(t('已保存'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function toggle(r: DictRule, v: boolean) {
  try {
    await dictApi.save({ ...r, enabled: v }, r.name)
    r.enabled = v
  } catch (e: any) {
    message.error(e.message)
  }
}

async function setOrder(r: DictRule, v: number | null) {
  if (v === null || v === r.sortNumber) return
  try {
    await dictApi.save({ ...r, sortNumber: v }, r.name)
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

function remove(names: string[]) {
  dialog.warning({
    title: t('删除字典规则'),
    content: t('确定删除 {0} 条字典规则？自带的规则可以通过“导入默认规则”恢复。', [names.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await dictApi.remove(names)
      checked.value = []
      await load()
    },
  })
}

function importDefaults() {
  dialog.info({
    title: t('导入默认规则'),
    content: t('恢复阅读 App 自带的字典规则。修改过的自带规则会被覆盖，自定义的规则不受影响。'),
    positiveText: t('导入'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await dictApi.importDefaults()
      await load()
      message.success(t('已导入默认规则'))
    },
  })
}

const showImport = ref(false)
const importText = ref('')
async function doImport() {
  try {
    const r = await dictApi.import(importText.value)
    message.success(t('已导入 {0} 条', [r.count]))
    showImport.value = false
    importText.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

function exportRules() {
  downloadText('dictRule.json', JSON.stringify(rules.value, null, 2))
}

/** 测试：用一条规则查询示例文字 */
const testRule = ref('')
const testWord = ref('')
const showTest = ref(false)
function test(r: DictRule) {
  testRule.value = r.name
  testWord.value = /[a-z]/i.test(r.name) || r.name.includes('英') ? 'hello' : '书'
  showTest.value = true
}

const isMobile = useIsMobile()
const selectAll = useSelectAll(rules, checked, (r: DictRule) => r.name)

/** 手机上与 App 一样只显示多选、名称、启用和菜单 */
const mobileCols = computed(() =>
  mobileColumns<DictRule>({
    name: (r) => r.name,
    sub: (r) => r.urlRule,
    enabled: (r) => r.enabled,
    onToggle: toggle,
    actions: (r) => [
      { key: 'test', label: t('测试'), onSelect: () => test(r) },
      { key: 'edit', label: canEdit.value ? t('编辑') : t('查看'), onSelect: () => (editing.value = { rule: { ...r }, oldName: r.name }) },
      { key: 'delete', label: t('删除'), danger: true, onSelect: () => remove([r.name]) },
    ],
  }),
)

const columns = computed<DataTableColumns<DictRule>>(() => isMobile.value ? mobileCols.value : [
  { type: 'selection' },
  {
    title: t('启用'), key: 'enabled', width: 70,
    render: (r) => h(NSwitch, { size: 'small', value: r.enabled, onUpdateValue: (v: boolean) => toggle(r, v) }),
  },
  { title: t('名称'), key: 'name', minWidth: 100 },
  { title: t('网址规则'), key: 'urlRule', ellipsis: { tooltip: { contentStyle: { maxWidth: '480px' } } }, render: (r) => h('span', { class: 'mono' }, r.urlRule) },
  {
    title: t('排序'), key: 'sortNumber', width: 100,
    render: (r) => h(NInputNumber, { size: 'tiny', value: r.sortNumber, showButton: false, onUpdateValue: (v: number | null) => setOrder(r, v) }),
  },
  {
    title: t('操作'), key: 'op', width: 170,
    render: (r) => h(NSpace, { size: 4, wrap: false }, () => [
      h(NButton, { size: 'tiny', onClick: () => test(r) }, () => t('测试')),
      h(NButton, { size: 'tiny', onClick: () => (editing.value = { rule: { ...r }, oldName: r.name }) }, () => (canEdit.value ? t('编辑') : t('查看'))),
      h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove([r.name]) }, () => t('删除')),
    ]),
  },
])
</script>

<template>
  <div>
    <div class="rule-toolbar">
      <p class="muted rule-desc">
        {{ t('阅读时选中文字，点“字典”即可用这些规则查词、翻译。与阅读 App 的字典规则格式相同：网址规则同书源的网址规则（{0} 为选中的文字），显示规则用来提取显示的内容。', [KEY_VAR]) }}
      </p>
      <n-checkbox v-if="isMobile" class="select-all" :checked="selectAll.all.value" :indeterminate="selectAll.some.value" @update:checked="selectAll.toggleAll">{{ t('全选') }}</n-checkbox>
      <n-button size="small" :disabled="!checked.length" type="error" ghost @click="remove(checked)">{{ t('删除选中') }}</n-button>
      <n-button size="small" @click="exportRules">{{ t('导出') }}</n-button>
      <n-button v-if="canEdit" size="small" @click="showImport = true">{{ t('导入') }}</n-button>
      <n-button size="small" @click="importDefaults">{{ t('导入默认规则') }}</n-button>
      <n-button v-if="canEdit" size="small" type="primary" @click="editing = { rule: blank(), oldName: '' }">{{ t('新建') }}</n-button>
    </div>
    <n-alert v-if="!canEdit" type="info" :bordered="false" style="margin-bottom: 12px">
      {{ t('字典规则会在服务器上请求网址、执行 JS，新建、修改和导入需要“添加书源”的权限。你可以使用、启用或停用、排序已有的规则。') }}
    </n-alert>
    <n-data-table
      v-model:checked-row-keys="checked"
      :columns="columns"
      :data="rules"
      :row-key="(r: DictRule) => r.name"
      :loading="loading"
      size="small"
      :class="{ 'm-table': isMobile }"
      :scroll-x="isMobile ? undefined : 720"
      :table-layout="isMobile ? 'fixed' : 'auto'"
    />

    <n-modal :show="!!editing" preset="card" :title="editing?.oldName ? (canEdit ? t('编辑字典规则') : t('查看字典规则')) : t('新建字典规则')"
      style="width: 640px; max-width: 94vw" :auto-focus="false" @update:show="(v: boolean) => !v && (editing = null)">
      <n-form v-if="editing" label-placement="top" :disabled="!canEdit">
        <n-form-item :label="t('名称')"><n-input v-model:value="editing.rule.name" /></n-form-item>
        <n-form-item :label="t('网址规则（urlRule）')">
          <n-input v-model:value="editing!.rule.urlRule" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono"
            placeholder="https://example.com/search?q={{key}}" />
        </n-form-item>
        <n-form-item :label="t('显示规则（showRule）')">
          <n-input v-model:value="editing!.rule.showRule" type="textarea" :autosize="{ minRows: 3, maxRows: 16 }" class="mono"
            :placeholder="t('留空时直接显示网页内容；可用 CSS、XPath、JSONPath、@js: 等规则提取')" />
        </n-form-item>
        <n-form-item :label="t('排序')"><n-input-number v-model:value="editing!.rule.sortNumber" /></n-form-item>
      </n-form>
      <template v-if="canEdit" #footer>
        <n-space justify="end"><n-button type="primary" :loading="saving" @click="save">{{ t('保存') }}</n-button></n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="showImport" preset="card" :title="t('导入字典规则')" style="width: 560px; max-width: 94vw">
      <n-input v-model:value="importText" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" :placeholder="t('粘贴字典规则 JSON（数组或单个规则），同名规则会被覆盖')" class="mono" />
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="doImport">{{ t('导入') }}</n-button></n-space>
      </template>
    </n-modal>

    <dict-modal v-model:show="showTest" :word="testWord" :rule-name="testRule" />
  </div>
</template>
