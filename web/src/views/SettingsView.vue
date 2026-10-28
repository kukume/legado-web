<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NDescriptions, NDescriptionsItem, NInput, NRadioButton, NRadioGroup, NSpace, useMessage } from 'naive-ui'
import ApiKeyManager from '@/components/ApiKeyManager.vue'
import OfflineManager from '@/components/OfflineManager.vue'
import BackupManager from '@/components/BackupManager.vue'
import ReadingStatsCard from '@/components/ReadingStatsCard.vue'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/utils/media'
import { LOCALES, locale, setLocale, t } from '@/i18n'

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()
const message = useMessage()

const pwd = ref({ old: '', new1: '', new2: '' })
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

    <n-card :title="t('阅读统计')" size="small" class="block">
      <reading-stats-card />
    </n-card>

    <n-card :title="t('外观')" size="small" class="block">
      <n-radio-group v-model:value="ui.themeMode" size="small">
        <n-radio-button value="auto">{{ t('跟随系统') }}</n-radio-button>
        <n-radio-button value="light">{{ t('浅色') }}</n-radio-button>
        <n-radio-button value="dark">{{ t('深色') }}</n-radio-button>
      </n-radio-group>
      <p class="muted small">{{ t('阅读页的主题、字体、排版在阅读时点击屏幕中间 → 设置 中调整，会同步到你的所有设备。') }}</p>
      <div class="lang-row">
        <span>{{ t('界面语言') }}</span>
        <n-radio-group :value="locale" size="small" @update:value="setLocale">
          <n-radio-button v-for="l in LOCALES" :key="l.value" :value="l.value">{{ l.label }}</n-radio-button>
        </n-radio-group>
      </div>
      <p class="muted small">{{ t('只对这台设备上的浏览器生效，切换后页面会刷新。') }}</p>
    </n-card>

    <n-card :title="t('离线阅读')" size="small" class="block">
      <offline-manager />
    </n-card>

    <n-card :title="t('备份与恢复')" size="small" class="block">
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
      <api-key-manager />
    </n-card>
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

.lang-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}
</style>
