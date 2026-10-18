<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns, useSelectAll } from '@/utils/mobileTable'
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCheckbox, NDataTable, NForm, NFormItem, NInput, NInputNumber, NModal, NSpace, NSwitch, NTag,
  useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import { txtTocApi } from '@/api'
import type { TxtTocRule } from '@/api/types'
import { downloadText } from '@/utils/media'
import { t } from '@/i18n'

/** TXT 目录规则：上传 TXT 时用来分章，与阅读 App 的 TXT 目录规则格式相同 */
const message = useMessage()
const dialog = useDialog()
const rules = ref<TxtTocRule[]>([])
const canEdit = ref(false)
const loading = ref(false)
const checked = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    const r = await txtTocApi.list()
    rules.value = r.rules
    canEdit.value = r.canEdit
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const blank = (): TxtTocRule => ({
  id: 0, name: '', rule: '', replacement: '', example: '', enable: true,
  serialNumber: rules.value.reduce((m, r) => Math.max(m, r.serialNumber), -1) + 1,
})

const editing = ref<TxtTocRule | null>(null)
const saving = ref(false)

async function save() {
  if (!editing.value) return
  saving.value = true
  try {
    await txtTocApi.save(editing.value)
    editing.value = null
    await load()
    message.success(t('已保存'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function toggle(r: TxtTocRule, v: boolean) {
  try {
    await txtTocApi.save({ ...r, enable: v })
    r.enable = v
  } catch (e: any) {
    message.error(e.message)
  }
}

function remove(ids: number[]) {
  dialog.warning({
    title: t('删除目录规则'),
    content: t('确定删除 {0} 条目录规则？自带的规则可以通过“导入默认规则”恢复。', [ids.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await txtTocApi.remove(ids)
      checked.value = []
      await load()
    },
  })
}

function importDefaults() {
  dialog.info({
    title: t('导入默认规则'),
    content: t('恢复阅读 App 自带的 TXT 目录规则。修改过的自带规则会被重置，自定义的规则不受影响。'),
    positiveText: t('导入'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await txtTocApi.importDefaults()
      await load()
      message.success(t('已导入默认规则'))
    },
  })
}

const showImport = ref(false)
const importText = ref('')
async function doImport() {
  try {
    const r = await txtTocApi.import(importText.value)
    message.success(t('已导入 {0} 条', [r.count]))
    showImport.value = false
    importText.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

function exportRules() {
  downloadText('txtTocRule.json', JSON.stringify(rules.value, null, 2))
}

/**
 * 在浏览器里用示例文字试一下正则（服务器用 Java 正则，语法基本相同，个别写法可能有差别）。
 * 不执行 js 处理。
 */
const testResult = computed<{ error?: string; titles?: string[] }>(() => {
  const r = editing.value
  if (!r?.rule || !r.example) return {}
  try {
    const re = new RegExp(r.rule, 'gm')
    return { titles: [...r.example.matchAll(re)].map((m) => m[0]).slice(0, 20) }
  } catch (e: any) {
    return { error: e.message }
  }
})

const isMobile = useIsMobile()
const selectAll = useSelectAll(rules, checked, (r: TxtTocRule) => r.id)

/** 手机上与 App 一样只显示多选、名称、启用和菜单 */
const mobileCols = computed(() =>
  mobileColumns<TxtTocRule>({
    name: (r) => [r.name, r.replacement ? h(NTag, { size: 'tiny', bordered: false }, () => 'js') : null],
    sub: (r) => (r.example ?? '').replace(/\n/g, ' / '),
    enabled: (r) => r.enable,
    onToggle: toggle,
    actions: (r) => [
      { key: 'edit', label: t('编辑'), onSelect: () => (editing.value = { ...r, example: r.example ?? '' }) },
      { key: 'delete', label: t('删除'), danger: true, onSelect: () => remove([r.id]) },
    ],
  }),
)

const columns = computed<DataTableColumns<TxtTocRule>>(() => isMobile.value ? mobileCols.value : [
  { type: 'selection' },
  {
    title: t('启用'), key: 'enable', width: 70,
    render: (r) => h(NSwitch, { size: 'small', value: r.enable, onUpdateValue: (v: boolean) => toggle(r, v) }),
  },
  { title: t('序号'), key: 'serialNumber', width: 64 },
  {
    title: t('名称'), key: 'name', minWidth: 160,
    render: (r) => [r.name, r.replacement ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-left:6px' }, () => 'js') : null],
  },
  {
    title: t('示例'), key: 'example', ellipsis: { tooltip: { contentStyle: { maxWidth: '420px' } } },
    render: (r) => (r.example ?? '').replace(/\n/g, ' / '),
  },
  {
    title: t('操作'), key: 'op', width: 120,
    render: (r) => h(NSpace, { size: 4, wrap: false }, () => [
      h(NButton, { size: 'tiny', onClick: () => (editing.value = { ...r, example: r.example ?? '' }) }, () => t('编辑')),
      h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove([r.id]) }, () => t('删除')),
    ]),
  },
])
</script>

<template>
  <div>
    <div class="rule-toolbar">
      <p class="muted rule-desc">
        {{ t('上传 TXT 时按序号依次尝试启用的规则，自动选出最合适的分章方式（与阅读 App 相同）。识别得不理想时，可以在书籍详情页为这本书单独指定规则。') }}
      </p>
      <n-checkbox v-if="isMobile" class="select-all" :checked="selectAll.all.value" :indeterminate="selectAll.some.value" @update:checked="selectAll.toggleAll">{{ t('全选') }}</n-checkbox>
      <n-button size="small" :disabled="!checked.length" type="error" ghost @click="remove(checked)">{{ t('删除选中') }}</n-button>
      <n-button size="small" @click="exportRules">{{ t('导出') }}</n-button>
      <n-button size="small" @click="showImport = true">{{ t('导入') }}</n-button>
      <n-button size="small" @click="importDefaults">{{ t('导入默认规则') }}</n-button>
      <n-button size="small" type="primary" @click="editing = blank()">{{ t('新建') }}</n-button>
    </div>
    <n-data-table
      v-model:checked-row-keys="checked"
      :columns="columns"
      :data="rules"
      :row-key="(r: TxtTocRule) => r.id"
      :loading="loading"
      size="small"
      :class="{ 'm-table': isMobile }"
      :scroll-x="isMobile ? undefined : 720"
      :table-layout="isMobile ? 'fixed' : 'auto'"
    />

    <n-modal :show="!!editing" preset="card" :title="editing?.id ? t('编辑目录规则') : t('新建目录规则')"
      style="width: 640px; max-width: 94vw" :auto-focus="false" @update:show="(v: boolean) => !v && (editing = null)">
      <n-form v-if="editing" label-placement="top">
        <n-form-item :label="t('名称')"><n-input v-model:value="editing.name" /></n-form-item>
        <n-form-item :label="t('目录正则')">
          <n-input v-model:value="editing.rule" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" class="mono"
            :placeholder="t('匹配章节标题所在的整行，例如 ^第.{1,9}章.{0,30}$')" />
        </n-form-item>
        <n-form-item :label="t('示例（用来测试正则）')">
          <n-input v-model:value="editing.example" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" />
        </n-form-item>
        <div v-if="testResult.error" class="toc-test error">{{ t('正则错误：{0}', [testResult.error]) }}</div>
        <div v-else-if="testResult.titles" class="toc-test muted">
          {{ t('匹配到 {0} 个标题', [testResult.titles.length]) }}<template v-if="testResult.titles.length">：{{ testResult.titles.map((t) => t.trim()).join(' / ') }}</template>
        </div>
        <n-form-item :label="t('js 处理（可选）')">
          <n-input v-model:value="editing.replacement" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono"
            :disabled="!canEdit && !editing.replacement"
            :placeholder="t('对匹配到的标题二次处理，返回值作为标题，返回空字符串表示不作为章节')" />
        </n-form-item>
        <p class="muted js-help">
          {{ t('可用变量：') }}<code>result</code> {{ t('匹配到的标题、') }}<code>index</code> {{ t('章节序号（从 1 开始）、') }}<code>prevTitle</code> {{ t('上一章标题、') }}<code>prevLength</code> {{ t('上一章长度、') }}<code>lastVolumeTitle</code> {{ t('最近的卷名、') }}<code>book</code> {{ t('书籍信息；') }}<code>java.putVolume(title)</code> {{ t('添加一个卷。') }}
          <template v-if="!canEdit">{{ t('js 在服务器上执行，需要“添加书源”的权限才能填写。') }}</template>
        </p>
        <div class="toc-row">
          <n-form-item :label="t('序号')" style="flex: 1"><n-input-number v-model:value="editing.serialNumber" /></n-form-item>
          <n-form-item :label="t('启用')"><n-switch v-model:value="editing.enable" /></n-form-item>
        </div>
      </n-form>
      <template #footer>
        <n-space justify="end"><n-button type="primary" :loading="saving" @click="save">{{ t('保存') }}</n-button></n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="showImport" preset="card" :title="t('导入目录规则')" style="width: 560px; max-width: 94vw">
      <n-alert v-if="!canEdit" type="info" :bordered="false" style="margin-bottom: 10px">{{ t('带 js 处理的规则需要“添加书源”的权限才能导入。') }}</n-alert>
      <n-input v-model:value="importText" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" :placeholder="t('粘贴 TXT 目录规则 JSON（数组或单个规则），id 相同的规则会被覆盖')" class="mono" />
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="doImport">{{ t('导入') }}</n-button></n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.toc-test {
  margin: -12px 0 16px;
  font-size: 12px;
  word-break: break-word;
}

.toc-test.error {
  color: var(--n-error-color, #d03050);
}

.js-help {
  margin: -12px 0 16px;
  font-size: 12px;
  line-height: 1.6;
}

.toc-row {
  display: flex;
  gap: 24px;
}
</style>
