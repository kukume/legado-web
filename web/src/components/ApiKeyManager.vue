<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NInput, NModal, NSpace, useDialog, useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { apiKeyApi, type ApiKeyInfo } from '@/api'
import { copyText, formatDate, formatTime } from '@/utils/media'
import { t } from '@/i18n'

const message = useMessage()
const dialog = useDialog()

const keys = ref<ApiKeyInfo[]>([])
const loading = ref(false)
const showCreate = ref(false)
const newName = ref('')
const createdKey = ref<string | null>(null)

const docsUrl = `${location.origin}/openapi/docs`

async function load() {
  loading.value = true
  try {
    keys.value = await apiKeyApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function create() {
  try {
    const r = await apiKeyApi.create(newName.value)
    createdKey.value = r.key
    showCreate.value = false
    newName.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

function remove(k: ApiKeyInfo) {
  dialog.warning({
    title: t('删除 API Key'),
    content: t('删除「{0}」后，使用它的程序将无法再访问。确定删除？', [k.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await apiKeyApi.remove(k.id)
      await load()
    },
  })
}

async function copy(text: string) {
  ;(await copyText(text)) ? message.success(t('已复制')) : message.error(t('复制失败'))
}

const columns: DataTableColumns<ApiKeyInfo> = [
  { title: t('名称'), key: 'name' },
  { title: t('密钥'), key: 'prefix', render: (k) => h('span', { class: 'mono' }, `${k.prefix}…`) },
  { title: t('创建时间'), key: 'createdAt', width: 150, render: (k) => formatDate(k.createdAt) },
  { title: t('最近使用'), key: 'lastUsedAt', width: 110, render: (k) => formatTime(k.lastUsedAt) },
  {
    title: t('操作'),
    key: 'op',
    width: 80,
    render: (k) => h(NButton, { size: 'tiny', type: 'error', quaternary: true, onClick: () => remove(k) }, () => t('删除')),
  },
]

</script>

<template>
  <div>
    <div class="bar">
      <span class="muted small">
        {{ t('API Key 用于在其他程序中访问你的书架和正文，拥有与你的账号相同的阅读权限，请妥善保管。') }}
      </span>
      <div style="flex: 1" />
      <n-button size="small" type="primary" @click="showCreate = true">{{ t('生成 API Key') }}</n-button>
    </div>

    <n-data-table :columns="columns" :data="keys" :loading="loading" size="small" :scroll-x="560" />

    <p class="small" style="margin: 12px 0 0">
      {{ t('接口文档（Swagger，可直接调试）：') }}<a :href="docsUrl" target="_blank" rel="noopener">{{ docsUrl }}</a>
    </p>

    <n-modal v-model:show="showCreate" preset="card" :title="t('生成 API Key')" style="max-width: 420px">
      <n-input v-model:value="newName" :placeholder="t('名称，例如：阅读插件')" maxlength="64" @keyup.enter="create" />
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="create">{{ t('生成') }}</n-button></n-space>
      </template>
    </n-modal>

    <n-modal :show="!!createdKey" preset="card" :title="t('API Key 已生成')" style="max-width: 520px" @update:show="(v: boolean) => !v && (createdKey = null)">
      <n-alert type="warning" :bordered="false" style="margin-bottom: 12px">
        {{ t('密钥只显示这一次，关闭后无法再查看，请立即复制保存。') }}
      </n-alert>
      <n-input :value="createdKey ?? ''" readonly class="mono" />
      <template #footer>
        <n-space justify="end">
          <n-button type="primary" @click="copy(createdKey!)">{{ t('复制') }}</n-button>
          <n-button @click="createdKey = null">{{ t('我已保存') }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.small {
  font-size: 13px;
}
</style>
