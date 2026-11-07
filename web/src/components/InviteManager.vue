<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NInput, NInputNumber, NModal, NSpace, NSwitch, NTag, useMessage, type DataTableColumns,
} from 'naive-ui'
import { inviteApi } from '@/api'
import type { Invite } from '@/api/types'
import { copyText, formatDate } from '@/utils/media'
import { t } from '@/i18n'
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns } from '@/utils/mobileTable'

const props = defineProps<{ admin?: boolean }>()
const message = useMessage()
const invites = ref<Invite[]>([])
const loading = ref(false)
const show = ref(false)
const form = ref({ count: 1, maxUses: 1, expireDays: 7 as number | null, note: '' })

async function load() {
  loading.value = true
  try {
    invites.value = await inviteApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function create() {
  try {
    await inviteApi.create(form.value)
    show.value = false
    await load()
    message.success(t('已生成'))
  } catch (e: any) {
    message.error(e.message)
  }
}

function inviteLink(code: string) {
  return `${location.origin}/login?invite=${code}`
}

async function copy(code: string, link = false) {
  ;(await copyText(link ? inviteLink(code) : code)) ? message.success(t('已复制')) : message.error(t('复制失败'))
}

async function setEnabled(i: Invite, v: boolean) {
  await inviteApi.setDisabled(i.id, !v)
  i.disabled = !v
}

async function remove(i: Invite) {
  await inviteApi.remove(i.id)
  load()
}

function status(i: Invite) {
  if (i.disabled) return h(NTag, { size: 'small', bordered: false }, () => t('已停用'))
  if (i.expiresAt && i.expiresAt < Date.now()) return h(NTag, { size: 'small', bordered: false, type: 'warning' }, () => t('已过期'))
  if (i.maxUses > 0 && i.usedCount >= i.maxUses) return h(NTag, { size: 'small', bordered: false, type: 'info' }, () => t('已用完'))
  return h(NTag, { size: 'small', bordered: false, type: 'success' }, () => t('可用'))
}

const desktopColumns: DataTableColumns<Invite> = [
  {
    title: t('邀请码'),
    key: 'code',
    render: (i) => h('span', { class: 'mono', style: 'font-weight:600;letter-spacing:1px' }, i.code),
  },
  { title: t('状态'), key: 's', width: 80, render: status },
  { title: t('使用'), key: 'usedCount', width: 80, render: (i) => `${i.usedCount}/${i.maxUses || '∞'}` },
  { title: t('过期时间'), key: 'expiresAt', width: 150, render: (i) => (i.expiresAt ? formatDate(i.expiresAt) : t('永不')) },
  ...(props.admin ? [{ title: t('创建者'), key: 'createdByName', width: 100 } as any] : []),
  { title: t('备注'), key: 'note', ellipsis: { tooltip: true } },
  {
    title: t('操作'),
    key: 'op',
    width: 220,
    render: (i) =>
      h(NSpace, { size: 4, wrap: false }, () => [
        h(NButton, { size: 'tiny', onClick: () => copy(i.code) }, () => t('复制')),
        h(NButton, { size: 'tiny', onClick: () => copy(i.code, true) }, () => t('复制链接')),
        h(NSwitch, { size: 'small', value: !i.disabled, onUpdateValue: (v: boolean) => setEnabled(i, v) }),
        h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove(i) }, () => t('删除')),
      ]),
  },
]

/** 手机上与用户列表一样：邀请码和状态（下面一行使用次数、过期日期、备注） | 启用 | ⋮ 菜单 */
const isMobile = useIsMobile()
const mobileCols = mobileColumns<Invite>({
  selectable: false,
  name: (i) => h('span', { class: 'm-code' }, [h('span', { class: 'mono' }, i.code), status(i)]),
  sub: (i) =>
    [
      t('使用 {0}', [`${i.usedCount}/${i.maxUses || '∞'}`]),
      // 手机上只显示日期，不显示创建者，留出位置给备注
      i.expiresAt ? t('{0} 过期', [formatDate(i.expiresAt).split(' ')[0]]) : t('永不过期'),
      i.note || '',
    ]
      .filter(Boolean)
      .join(' · '),
  enabled: (i) => !i.disabled,
  onToggle: setEnabled,
  actions: (i) => [
    { key: 'copy', label: t('复制'), onSelect: () => copy(i.code) },
    { key: 'link', label: t('复制链接'), onSelect: () => copy(i.code, true) },
    { key: 'delete', label: t('删除'), danger: true, onSelect: () => remove(i) },
  ],
})
const columns = computed(() => (isMobile.value ? mobileCols : desktopColumns))
</script>

<template>
  <div>
    <div class="bar">
      <div style="flex: 1" />
      <n-button size="small" type="primary" @click="show = true">{{ t('生成邀请码') }}</n-button>
    </div>
    <n-data-table
      :columns="columns"
      :data="invites"
      :loading="loading"
      size="small"
      :class="{ 'm-table': isMobile }"
      :scroll-x="isMobile ? undefined : 700"
      :table-layout="isMobile ? 'fixed' : 'auto'"
      :pagination="{ pageSize: 20 }"
    />
    <n-modal v-model:show="show" preset="card" :title="t('生成邀请码')" style="max-width: 420px">
      <n-space vertical>
        <n-alert type="warning" :bordered="false" :title="t('请只邀请信任的人')">
          {{ t('被邀请的人注册后，一旦获得「添加书源」权限，就能让服务器运行自定义脚本（书源本质是可执行的 JavaScript），存在较高安全风险。请谨慎开放添加书源权限。') }}
        </n-alert>
        <template v-if="admin">
          <label class="small">{{ t('数量') }} <n-input-number v-model:value="form.count" :min="1" :max="100" size="small" /></label>
          <label class="small">{{ t('每个可用次数（0 为不限）') }} <n-input-number v-model:value="form.maxUses" :min="0" size="small" /></label>
        </template>
        <label class="small">{{ t('有效天数（留空为永久）') }} <n-input-number v-model:value="form.expireDays" :min="1" clearable size="small" /></label>
        <n-input v-model:value="form.note" :placeholder="t('备注（可选）')" />
      </n-space>
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="create">{{ t('生成') }}</n-button></n-space>
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

/* 手机：邀请码和状态标签在同一行 */
:deep(.m-code) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  letter-spacing: 1px;
}

label.small {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
