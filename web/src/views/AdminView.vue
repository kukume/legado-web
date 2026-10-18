<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NIcon, NInput, NInputNumber, NModal, NRadioButton, NRadioGroup,
  NSpace, NSwitch, NTabs, NTabPane, NTag, useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import { BookOutline, GlobeOutline, HomeOutline, LockClosedOutline, PeopleOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import InviteManager from '@/components/InviteManager.vue'
import { adminApi } from '@/api'
import type { User } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDate, formatTime } from '@/utils/media'
import { t } from '@/i18n'

const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const tab = ref('users')
const users = ref<User[]>([])
const loading = ref(false)
const settings = ref<Record<string, string>>({})

async function loadUsers() {
  loading.value = true
  try {
    users.value = await adminApi.users()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 接口返回的密钥占位符，原样提交表示不修改 */
const SECRET_MASK = '********'
const secretSet = ref(false)

/** 加载 / 保存后的设置，用来判断是否有未保存的修改 */
const savedSettings = ref('{}')
const dirty = computed(() => JSON.stringify(settings.value) !== savedSettings.value)

function applySettings(s: Record<string, string>) {
  settings.value = s
  savedSettings.value = JSON.stringify(s)
  secretSet.value = s.capSecret === SECRET_MASK
}

async function loadSettings() {
  applySettings(await adminApi.settings())
}

function resetSettings() {
  settings.value = JSON.parse(savedSettings.value)
}

onMounted(() => {
  loadUsers()
  loadSettings()
})

async function update(u: User, data: { enabled?: boolean; password?: string; canAddSource?: boolean }) {
  try {
    const nu = await adminApi.updateUser(u.id, data)
    Object.assign(u, nu)
    message.success(t('已更新'))
  } catch (e: any) {
    message.error(e.message)
  }
}

const resetTarget = ref<User | null>(null)
const resetPwd = ref('')

function remove(u: User) {
  dialog.warning({
    title: t('删除用户'),
    content: t('确定删除用户「{0}」？该用户的书架数据不会被立即清理。', [u.username]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await adminApi.deleteUser(u.id)
      await loadUsers()
    },
  })
}

const nameOf = (id: number | null) => users.value.find((u) => u.id === id)?.username ?? '-'

const columns: DataTableColumns<User> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: t('用户名'), key: 'username' },
  {
    // 管理员只有第一个注册的用户，不能在这里修改
    title: t('角色'),
    key: 'role',
    width: 90,
    render: (u) =>
      u.role === 'admin'
        ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => t('管理员'))
        : h('span', { class: 'muted' }, t('用户')),
  },
  {
    title: t('启用'),
    key: 'enabled',
    width: 70,
    render: (u) => h(NSwitch, { size: 'small', value: u.enabled, disabled: u.id === auth.user?.id, onUpdateValue: (v: boolean) => update(u, { enabled: v }) }),
  },
  {
    title: t('添加书源'),
    key: 'canAddSource',
    width: 90,
    render: (u) =>
      u.role === 'admin'
        ? h('span', { class: 'muted', style: 'font-size:12px' }, t('管理员'))
        : h(NSwitch, {
            size: 'small',
            value: u.canAddSource,
            onUpdateValue: (v: boolean) => update(u, { canAddSource: v }),
          }),
  },
  { title: t('邀请人'), key: 'invitedBy', width: 100, render: (u) => nameOf(u.invitedBy) },
  { title: t('注册时间'), key: 'createdAt', width: 150, render: (u) => formatDate(u.createdAt) },
  { title: t('最近登录'), key: 'lastLoginAt', width: 110, render: (u) => formatTime(u.lastLoginAt) },
  {
    title: t('操作'),
    key: 'op',
    width: 150,
    render: (u) =>
      h(NSpace, { size: 4, wrap: false }, () => [
        h(NButton, { size: 'tiny', onClick: () => { resetTarget.value = u; resetPwd.value = '' } }, () => t('重置密码')),
        u.id !== auth.user?.id ? h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => remove(u) }, () => t('删除')) : null,
      ]),
  },
]

async function doReset() {
  if (!resetTarget.value || resetPwd.value.length < 6) return message.warning(t('密码至少 6 位'))
  await update(resetTarget.value, { password: resetPwd.value })
  resetTarget.value = null
}

const showCreate = ref(false)
const createForm = ref({ username: '', password: '' })
async function createUser() {
  try {
    await adminApi.createUser(createForm.value.username, createForm.value.password)
    showCreate.value = false
    createForm.value = { username: '', password: '' }
    await loadUsers()
  } catch (e: any) {
    message.error(e.message)
  }
}

const testingBrowser = ref(false)
async function testBrowser() {
  testingBrowser.value = true
  try {
    const r = await adminApi.testBrowser(settings.value.browserCdp || '')
    message.success(t('连接成功：{0}', [r.browser]))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    testingBrowser.value = false
  }
}

const savingSettings = ref(false)
async function saveSettings() {
  savingSettings.value = true
  try {
    applySettings(await adminApi.saveSettings(settings.value))
    await auth.loadConfig()
    await auth.fetchMe()
    message.success(t('设置已保存'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    savingSettings.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header"><h2>{{ t('系统管理') }}</h2></div>
    <n-tabs v-model:value="tab" type="line">
      <n-tab-pane name="users" :tab="t('用户 ({0})', [users.length])">
        <div class="bar">
          <div style="flex: 1" />
          <n-button size="small" type="primary" @click="showCreate = true">{{ t('添加用户') }}</n-button>
        </div>
        <n-data-table :columns="columns" :data="users" :loading="loading" size="small" :scroll-x="920" />
      </n-tab-pane>

      <n-tab-pane name="invites" :tab="t('邀请码')">
        <invite-manager admin />
      </n-tab-pane>

      <n-tab-pane name="settings" :tab="t('系统设置')">
        <div class="settings-page">
          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="HomeOutline" />{{ t('基本') }}</h3>
            <div class="set-card">
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('站点名称') }}</div>
                  <div class="set-desc">{{ t('显示在页面标题和登录页') }}</div>
                </div>
                <div class="set-control wide"><n-input v-model:value="settings.siteName" /></div>
              </div>
            </div>
          </section>

          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="PeopleOutline" />{{ t('注册与邀请') }}</h3>
            <div class="set-card">
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('注册方式') }}</div>
                  <div class="set-desc">{{ t('邀请码注册时，新用户需要填写管理员或其他用户生成的邀请码') }}</div>
                </div>
                <div class="set-control">
                  <n-radio-group v-model:value="settings.registrationMode" size="small">
                    <n-radio-button value="open">{{ t('开放注册') }}</n-radio-button>
                    <n-radio-button value="invite">{{ t('邀请码注册') }}</n-radio-button>
                    <n-radio-button value="closed">{{ t('关闭注册') }}</n-radio-button>
                  </n-radio-group>
                </div>
              </div>
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('允许用户生成邀请码') }}</div>
                  <div class="set-desc">{{ t('普通用户可以在“我的”页面生成邀请码，邀请朋友注册') }}</div>
                </div>
                <div class="set-control">
                  <n-switch :value="settings.allowUserInvite === 'true'" @update:value="(v: boolean) => (settings.allowUserInvite = String(v))" />
                </div>
              </div>
              <div v-if="settings.allowUserInvite === 'true'" class="set-row sub">
                <div class="set-text">
                  <div class="set-label">{{ t('每个用户邀请码上限') }}</div>
                </div>
                <div class="set-control">
                  <n-input-number :value="Number(settings.userInviteLimit || 0)" :min="0" size="small" class="num" @update:value="(v: number | null) => (settings.userInviteLimit = String(v ?? 0))" />
                </div>
              </div>
            </div>
          </section>

          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="ShieldCheckmarkOutline" />{{ t('登录验证') }}</h3>
            <div class="set-card">
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('登录注册人机验证') }}</div>
                  <div class="set-desc">{{ t('使用 Cap 验证码') }}</div>
                </div>
                <div class="set-control">
                  <n-switch :value="settings.captchaEnabled === 'true'" @update:value="(v: boolean) => (settings.captchaEnabled = String(v))" />
                </div>
              </div>
              <template v-if="settings.captchaEnabled === 'true'">
                <div class="set-row sub stack">
                  <div class="set-label">{{ t('Cap 实例地址') }}</div>
                  <n-input v-model:value="settings.capEndpoint" placeholder="https://cap.example.com" />
                </div>
                <div class="set-row sub stack">
                  <div class="set-label">Site Key</div>
                  <n-input v-model:value="settings.capSiteKey" :placeholder="t('Cap 控制台中密钥的 Site Key')" />
                </div>
                <div class="set-row sub stack">
                  <div class="set-label">Secret</div>
                  <n-input
                    v-model:value="settings.capSecret"
                    type="password"
                    show-password-on="click"
                    :placeholder="t('该 Site Key 对应的 Secret（不是控制台的管理密钥）')"
                    autocomplete="off"
                    @focus="settings.capSecret === SECRET_MASK && (settings.capSecret = '')"
                    @blur="!settings.capSecret && secretSet && (settings.capSecret = SECRET_MASK)"
                  />
                </div>
                <div class="set-row sub">
                  <div class="set-desc">
                    {{ t('需要先部署') }} <a href="https://capjs.js.org/guide/standalone/" target="_blank" rel="noopener">Cap Standalone</a>{{ t('， 在它的控制台创建一个密钥，把 Site Key 和 Secret 填到这里。三项都填写后才会生效，保存后登录页立即显示验证码。 如果 Cap 服务出问题导致无法登录，可以设置环境变量') }} <code>LEGADO_CAPTCHA_DISABLED=true</code> {{ t('重启服务临时关闭。') }}
                  </div>
                </div>
              </template>
            </div>
          </section>

          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="LockClosedOutline" />{{ t('书源与安全') }}</h3>
            <div class="set-card">
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('允许所有用户添加书源') }}</div>
                  <div class="set-desc">{{ t('书源中的 JavaScript 在服务器上执行，可以让服务器访问外部网址，请只对信任的用户开放；也可以在“用户”列表中单独授权某些用户') }}</div>
                </div>
                <div class="set-control">
                  <n-switch :value="settings.allowUserSources === 'true'" @update:value="(v: boolean) => (settings.allowUserSources = String(v))" />
                </div>
              </div>
              <div class="set-row stack">
                <div class="set-text">
                  <div class="set-label">{{ t('允许访问的内网地址') }}</div>
                  <div class="set-desc">{{ t('书源、书籍地址和浏览器始终不能访问本机和内网（包括云服务器元数据地址），自己局域网里的书源服务可以加到这里') }}</div>
                </div>
                <n-input
                  v-model:value="settings.privateNetworkAllowlist"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  class="mono"
                  :placeholder="t('每行一个：主机名（nas.lan）、通配（*.lan）、IP（192.168.1.10）或网段（192.168.1.0/24）')"
                />
              </div>
            </div>
          </section>

          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="GlobeOutline" />{{ t('浏览器') }}</h3>
            <div class="set-card">
              <div class="set-row stack">
                <div class="set-text">
                  <div class="set-label">{{ t('浏览器调试地址') }}</div>
                  <div class="set-desc">{{ t('浏览器的 Chrome DevTools Protocol 地址，webView 书源和远程浏览器使用，保存后立即生效。每次打开网页都使用新的浏览器上下文（相当于无痕窗口），用完销毁。调试端口没有密码，不要暴露到公网。') }}</div>
                </div>
                <div class="browser-cdp">
                  <n-input
                    v-model:value="settings.browserCdp"
                    class="mono"
                    placeholder="http://browser:9222"
                  />
                  <n-button :loading="testingBrowser" @click="testBrowser">{{ t('测试连接') }}</n-button>
                </div>
              </div>
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('使用远程浏览器') }}</div>
                  <div class="set-desc">{{ t('书源要打开网页让用户操作（人机验证、登录）时，在服务器的浏览器里打开并把画面实时传给用户，同一时间只打开一个，其余排队；关闭时下载网页交给前端显示。') }}</div>
                </div>
                <div class="set-control">
                  <n-switch :value="settings.remoteBrowserEnabled === 'true'" @update:value="(v: boolean) => (settings.remoteBrowserEnabled = String(v))" />
                </div>
              </div>
              <div v-if="settings.remoteBrowserEnabled === 'true' && settings.browserAvailable === 'false'" class="set-row sub">
                <n-alert type="warning" :bordered="false" class="set-alert">
                  {{ t('没有设置浏览器调试地址，远程浏览器不会生效。启动一个浏览器（如 Docker 的 chromedp/headless-shell），把它的调试地址填到上面。') }}
                </n-alert>
              </div>
            </div>
          </section>

          <section class="set-group">
            <h3 class="set-group-title"><n-icon :component="BookOutline" />{{ t('本地书籍') }}</h3>
            <div class="set-card">
              <div class="set-row">
                <div class="set-text">
                  <div class="set-label">{{ t('允许上传本地书籍') }}</div>
                  <div class="set-desc">{{ t('用户可以把 EPUB、TXT、MOBI、AZW3、UMD、PDF 上传到自己的书架') }}</div>
                </div>
                <div class="set-control">
                  <n-switch :value="settings.localBookEnabled !== 'false'" @update:value="(v: boolean) => (settings.localBookEnabled = String(v))" />
                </div>
              </div>
              <template v-if="settings.localBookEnabled !== 'false'">
                <div class="set-row sub">
                  <div class="set-text"><div class="set-label">{{ t('单个文件上限（MB）') }}</div></div>
                  <div class="set-control">
                    <n-input-number :value="Number(settings.localBookMaxMb || 50)" :min="1" :max="1024" size="small" class="num" @update:value="(v: number | null) => (settings.localBookMaxMb = String(v ?? 50))" />
                  </div>
                </div>
                <div class="set-row sub">
                  <div class="set-text"><div class="set-label">{{ t('每个用户总空间（MB）') }}</div></div>
                  <div class="set-control">
                    <n-input-number :value="Number(settings.localBookQuotaMb || 100)" :min="1" size="small" class="num" @update:value="(v: number | null) => (settings.localBookQuotaMb = String(v ?? 100))" />
                  </div>
                </div>
              </template>
            </div>
          </section>

          <!-- 固定在底部，有改动时提示 -->
          <div class="save-bar" :class="{ dirty }">
            <span class="save-hint">{{ dirty ? t('有未保存的修改') : t('所有修改已保存') }}</span>
            <n-button v-if="dirty" size="small" @click="resetSettings">{{ t('撤销') }}</n-button>
            <n-button type="primary" size="small" :disabled="!dirty" :loading="savingSettings" @click="saveSettings">{{ t('保存设置') }}</n-button>
          </div>
        </div>
      </n-tab-pane>
    </n-tabs>

    <n-modal :show="!!resetTarget" preset="dialog" :title="t('重置 {0} 的密码', [resetTarget?.username])" :positive-text="t('确定')"
      :negative-text="t('取消')" @positive-click="doReset" @update:show="(v: boolean) => !v && (resetTarget = null)">
      <n-input v-model:value="resetPwd" type="password" :placeholder="t('新密码（至少 6 位）')" />
    </n-modal>

    <n-modal v-model:show="showCreate" preset="card" :title="t('添加用户')" style="max-width: 400px">
      <n-space vertical>
        <n-input v-model:value="createForm.username" :placeholder="t('用户名')" />
        <n-input v-model:value="createForm.password" type="password" :placeholder="t('密码（至少 6 位）')" />      </n-space>
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="createUser">{{ t('创建') }}</n-button></n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  margin-bottom: 10px;
}
.browser-cdp {
  display: flex;
  gap: 8px;
  width: 100%;
}

.settings-page {
  max-width: 760px;
  padding-top: 4px;
}

.set-group {
  margin-bottom: 22px;
}

.set-group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-2);
  letter-spacing: 0.02em;
}

.set-card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
}

.set-row {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 14px 18px;
}

.set-row + .set-row {
  border-top: 1px solid var(--app-border);
}

/* 从属于上一项（开启后才显示）：缩进并去掉分隔线 */
.set-row.sub {
  padding-top: 4px;
  padding-left: 34px;
  border-top: none;
}

.set-row.stack {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.set-text {
  flex: 1;
  min-width: 0;
}

.set-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text);
}

.set-desc {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-2);
}

.set-control {
  flex-shrink: 0;
}

.set-control.wide {
  width: 260px;
}

.set-alert {
  width: 100%;
}

.num {
  width: 120px;
}

.save-bar {
  position: sticky;
  bottom: 12px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px 10px 18px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  box-shadow: 0 4px 16px rgb(0 0 0 / 8%);
}

.save-bar.dirty {
  border-color: color-mix(in srgb, var(--app-accent) 50%, var(--app-border));
}

.save-hint {
  flex: 1;
  font-size: 13px;
  color: var(--app-text-2);
}

.save-bar.dirty .save-hint {
  color: var(--app-accent);
  font-weight: 500;
}

@media (max-width: 767px) {
  .set-row {
    flex-wrap: wrap;
    gap: 10px;
    padding: 12px 14px;
  }

  .set-row.sub {
    padding-left: 14px;
  }

  .set-control.wide {
    width: 100%;
  }

  /* 单选按钮组放不下时换到下一行 */
  .set-control:has(.n-radio-group) {
    width: 100%;
  }

  .save-bar {
    bottom: calc(64px + env(safe-area-inset-bottom));
  }
}
</style>
