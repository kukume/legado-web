<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NCheckbox, NDescriptions, NDescriptionsItem, NIcon, NInput, NRadioButton, NRadioGroup, NSpace, NSwitch, useMessage } from 'naive-ui'
import type { NavLayout } from '@/utils/nav'
import { ChevronForward, LogoGithub } from '@vicons/ionicons5'
import { useUpdateStore } from '@/stores/update'
import { useNavBars } from '@/composables/useNavBars'
import ApiKeyManager from '@/components/ApiKeyManager.vue'
import HelpTip from '@/components/HelpTip.vue'
import OfflineManager from '@/components/OfflineManager.vue'
import BackupManager from '@/components/BackupManager.vue'
import ReadingStatsCard from '@/components/ReadingStatsCard.vue'
import { authApi, prefsApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/utils/media'
import { LOCALES, locale, setLocale, t } from '@/i18n'

const auth = useAuthStore()
const ui = useUiStore()
const updates = useUpdateStore()
const router = useRouter()
const message = useMessage()

const pwd = ref({ old: '', new1: '', new2: '' })
const apiKeys = ref<InstanceType<typeof ApiKeyManager> | null>(null)

// 多端阅读同步：每个用户自己的设置，默认关闭（服务器只为开启的用户推送进度）
const progressSync = ref(false)
const progressSyncSaving = ref(false)
prefsApi.get<boolean>('progressSync').then((v) => (progressSync.value = v === true)).catch(() => {})
async function setProgressSync(v: boolean) {
  progressSyncSaving.value = true
  try {
    await prefsApi.set('progressSync', v)
    progressSync.value = v
  } catch (e: any) {
    message.error(e.message)
  } finally {
    progressSyncSaving.value = false
  }
}
// 导航栏：书架、搜索、书源、我的固定显示；Z-Library 需管理员开启；管理只在电脑侧栏（手机底栏始终隐藏）
const { available, hiddenHere } = useNavBars()
const navBars = computed(() => [
  { key: 'side' as const, title: t('侧栏（电脑）'), items: available.value },
  { key: 'bottom' as const, title: t('底栏（手机）'), items: available.value.filter((i) => !i.sideOnly) },
])

/** 导航栏里隐藏了的入口在“我的”里显示的说明 */
const ENTRY_DESC: Record<string, string> = {
  explore: t('浏览书源的发现页'),
  rules: t('替换净化、字典、TXT 目录规则'),
  zlibrary: t('搜索下载电子书'),
  admin: t('用户、邀请码、系统设置'),
}

async function toggleNav(bar: keyof NavLayout, name: string, show: boolean) {
  const hidden = new Set(ui.navHidden[bar])
  if (show) hidden.delete(name)
  else hidden.add(name)
  try {
    await ui.setNavHidden({ ...ui.navHidden, [bar]: [...hidden] })
  } catch (e: any) {
    message.error(e.message)
  }
}

const saving = ref(false)

async function changePassword() {
  if (pwd.value.new1.length < 6) return message.warning(t('新密码至少 6 位'))
  if (pwd.value.new1 !== pwd.value.new2) return message.warning(t('两次输入的密码不一致'))
  saving.value = true
  try {
    const r = await authApi.changePassword(pwd.value.old, pwd.value.new1)
    auth.applyToken(r.token)
    pwd.value = { old: '', new1: '', new2: '' }
    message.success(t('密码已修改，其他设备需要重新登录'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="page narrow">
    <div class="page-header"><h2>{{ t('我的') }}</h2></div>

    <n-card :title="t('账号')" size="small" class="block">
      <n-descriptions :column="1" label-placement="left" size="small">
        <n-descriptions-item :label="t('用户名')">{{ auth.user?.username }}</n-descriptions-item>
        <n-descriptions-item :label="t('注册时间')">{{ formatDate(auth.user?.createdAt) }}</n-descriptions-item>
      </n-descriptions>
      <template #header-extra>
        <n-button size="small" @click="logout">{{ t('退出登录') }}</n-button>
      </template>
    </n-card>

    <!-- 当前设备的导航栏里没有显示的入口（手机底栏始终不显示管理；隐藏了的发现、规则等）在这里进入 -->
    <div v-if="hiddenHere.length" class="entries block">
      <router-link v-for="i in hiddenHere" :key="i.name" :to="{ name: i.name }" class="entry">
        <n-icon :component="i.icon" size="20" class="entry-icon" />
        <div class="entry-text">
          <div class="entry-title">
            {{ i.name === 'admin' ? t('系统管理') : i.label }}
            <span v-if="i.name === 'admin' && updates.hasUpdate" class="entry-badge">{{ t('有新版本') }}</span>
          </div>
          <div class="muted small">{{ ENTRY_DESC[i.name] }}</div>
        </div>
        <n-icon :component="ChevronForward" size="18" class="muted" />
      </router-link>
    </div>

    <n-card :title="t('阅读统计')" size="small" class="block">
      <reading-stats-card />
    </n-card>

    <n-card :title="t('外观')" size="small" class="block">
      <div class="pref-row">
        <span class="pref-label">{{ t('主题') }}</span>
        <n-radio-group v-model:value="ui.themeMode" size="small">
          <n-radio-button value="auto">{{ t('跟随系统') }}</n-radio-button>
          <n-radio-button value="light">{{ t('浅色') }}</n-radio-button>
          <n-radio-button value="dark">{{ t('深色') }}</n-radio-button>
        </n-radio-group>
      </div>
      <div class="pref-row">
        <span class="pref-label">{{ t('界面语言') }}</span>
        <n-radio-group :value="locale" size="small" @update:value="setLocale">
          <n-radio-button v-for="l in LOCALES" :key="l.value" :value="l.value">{{ l.label }}</n-radio-button>
        </n-radio-group>
      </div>
    </n-card>

    <n-card :title="t('导航栏')" size="small" class="block">
      <div class="nav-cfg">
        <div v-for="bar in navBars" :key="bar.key" class="nav-col">
          <div class="pref-label">{{ bar.title }}</div>
          <n-checkbox
            v-for="i in bar.items"
            :key="i.name"
            :checked="i.fixed || !ui.navHidden[bar.key].includes(i.name)"
            :disabled="i.fixed"
            @update:checked="(v: boolean) => toggleNav(bar.key, i.name, v)"
          >
            {{ i.label }}
          </n-checkbox>
        </div>
      </div>
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('多端阅读同步') }}<help-tip>{{ t('同时在多个设备或 IDE 插件里读同一本书时，自动跟随翻章和滚动') }}</help-tip></template>
      <template #header-extra>
        <n-switch :value="progressSync" :loading="progressSyncSaving" @update:value="setProgressSync" />
      </template>
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('离线阅读') }}<help-tip>{{ t('在书籍详情页点“离线下载”，没有网络也能阅读，进度联网后自动同步') }}</help-tip></template>
      <offline-manager />
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('备份与恢复') }}<help-tip>{{ t('备份文件与阅读 App 通用') }}</help-tip></template>
      <backup-manager />
    </n-card>

    <n-card :title="t('修改密码')" size="small" class="block">
      <n-space vertical style="max-width: 360px">
        <n-input v-model:value="pwd.old" type="password" :placeholder="t('原密码')" />
        <n-input v-model:value="pwd.new1" type="password" :placeholder="t('新密码（至少 6 位）')" />
        <n-input v-model:value="pwd.new2" type="password" :placeholder="t('确认新密码')" />
        <n-button type="primary" :loading="saving" @click="changePassword">{{ t('修改密码') }}</n-button>
      </n-space>
    </n-card>

    <n-card :title="t('API Key（开放接口）')" size="small" class="block">
      <template #header-extra>
        <n-button size="small" type="primary" @click="apiKeys?.openCreate()">{{ t('生成 API Key') }}</n-button>
      </template>
      <api-key-manager ref="apiKeys" />
    </n-card>

    <a class="project-link muted" href="https://github.com/kukume/legado-web" target="_blank" rel="noopener">
      <n-icon :component="LogoGithub" />kukume/legado-web
    </a>
  </div>
</template>

<style scoped>
.narrow {
  max-width: 820px;
}

.block {
  margin-bottom: 16px;
}

.small {
  font-size: 13px;
}

.pref-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.pref-row + .pref-row {
  margin-top: 12px;
}

.pref-label {
  min-width: 4em;
}

/* 导航栏里隐藏了的入口（列表，与卡片同宽） */
.entries {
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  overflow: hidden;
}

.entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  color: var(--app-text);
  text-decoration: none;
}

.entry + .entry {
  border-top: 1px solid var(--app-border);
}

.entry:hover {
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}

.entry-icon {
  color: var(--app-accent);
}

.entry-text {
  flex: 1;
  min-width: 0;
}

.entry-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.entry-badge {
  padding: 0 6px;
  border-radius: 8px;
  background: var(--app-accent);
  color: #fff;
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
}

/* 导航栏设置：侧栏、底栏两列，窄屏上下排列 */
.nav-cfg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.nav-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nav-col .pref-label {
  margin-bottom: 2px;
  color: var(--app-text-2);
  font-size: 13px;
}

@media (max-width: 480px) {
  .nav-cfg {
    grid-template-columns: 1fr;
  }
}

/* 页面底部的项目地址 */
.project-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 8px 0 24px;
  font-size: 13px;
  text-decoration: none;
}

.project-link:hover {
  color: var(--app-text);
}
</style>
