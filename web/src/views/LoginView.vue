<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NTabs, NTabPane, NAlert, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import CapCaptcha from '@/components/CapCaptcha.vue'
import { LOCALES, locale, setLocale, t } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const message = useMessage()

const tab = ref<'login' | 'register'>('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', password2: '', inviteCode: '' })

const mode = computed(() => auth.config?.registrationMode ?? 'invite')
const firstUser = computed(() => auth.config && !auth.config.hasUsers)
const canRegister = computed(() => firstUser.value || mode.value !== 'closed')
const needInvite = computed(() => !firstUser.value && mode.value === 'invite')
const captcha = computed(() => auth.config?.captcha ?? null)
const captchaToken = ref('')
const capRef = ref<InstanceType<typeof CapCaptcha>>()

onMounted(async () => {
  try {
    await auth.loadConfig()
    if (firstUser.value) tab.value = 'register'
    const code = route.query.invite as string | undefined
    if (code) {
      form.inviteCode = code
      tab.value = 'register'
    }
  } catch (e: any) {
    message.error(e.message)
  }
})

async function submit() {
  if (!form.username || !form.password) return message.warning(t('请输入用户名和密码'))
  if (captcha.value && !captchaToken.value) return message.warning(t('请先完成人机验证'))
  loading.value = true
  try {
    if (tab.value === 'login') {
      await auth.login(form.username, form.password, captchaToken.value || undefined)
    } else {
      if (form.password !== form.password2) {
        message.warning(t('两次输入的密码不一致'))
        return
      }
      await auth.register(form.username, form.password, form.inviteCode || undefined, captchaToken.value || undefined)
      message.success(firstUser.value ? t('管理员账号已创建') : t('注册成功'))
    }
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (e: any) {
    message.error(e.message)
    // 验证码 token 只能用一次，失败后要重新验证
    capRef.value?.reset()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="logo">
        <img src="/favicon.svg" alt="" />
        <h1>{{ auth.siteName }}</h1>
        <p class="muted">{{ t('自建书源 · 多端同步的网页阅读器') }}</p>
      </div>
      <n-alert v-if="firstUser" type="info" :bordered="false" style="margin-bottom: 16px">
        {{ t('系统还没有用户，第一个注册的账号将成为管理员。') }}
      </n-alert>
      <n-tabs v-model:value="tab" type="segment" animated>
        <n-tab-pane name="login" :tab="t('登录')" :disabled="!!firstUser" />
        <n-tab-pane v-if="canRegister" name="register" :tab="t('注册')" />
      </n-tabs>
      <n-form style="margin-top: 16px" @submit.prevent="submit">
        <n-form-item :label="t('用户名')" :show-feedback="false" style="margin-bottom: 14px">
          <n-input v-model:value="form.username" :placeholder="t('用户名')" autocomplete="username" @keyup.enter="submit" />
        </n-form-item>
        <n-form-item :label="t('密码')" :show-feedback="false" style="margin-bottom: 14px">
          <n-input
            v-model:value="form.password"
            type="password"
            show-password-on="click"
            :placeholder="t('密码（至少 6 位）')"
            :autocomplete="tab === 'login' ? 'current-password' : 'new-password'"
            @keyup.enter="submit"
          />
        </n-form-item>
        <template v-if="tab === 'register'">
          <n-form-item :label="t('确认密码')" :show-feedback="false" style="margin-bottom: 14px">
            <n-input v-model:value="form.password2" type="password" :placeholder="t('再次输入密码')" autocomplete="new-password" />
          </n-form-item>
          <n-form-item v-if="needInvite" :label="t('邀请码')" :show-feedback="false" style="margin-bottom: 14px">
            <n-input v-model:value="form.inviteCode" :placeholder="t('请输入邀请码')" />
          </n-form-item>
        </template>
        <n-form-item v-if="captcha" :show-feedback="false" style="margin-bottom: 14px">
          <CapCaptcha ref="capRef" v-model="captchaToken" :endpoint="captcha.endpoint" @error="(m: string) => message.error(m)" />
        </n-form-item>
        <n-button type="primary" block :loading="loading" attr-type="submit" size="large">
          {{ tab === 'login' ? t('登录') : firstUser ? t('创建管理员') : t('注册') }}
        </n-button>
      </n-form>
      <p v-if="!canRegister && tab === 'login'" class="muted hint">{{ t('系统已关闭注册，请联系管理员。') }}</p>
      <div class="langs">
        <button v-for="l in LOCALES" :key="l.value" type="button" :class="{ on: l.value === locale }" @click="l.value !== locale && setLocale(l.value)">
          {{ l.label }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background:
    radial-gradient(circle at 20% 10%, color-mix(in srgb, var(--app-accent) 12%, transparent), transparent 40%),
    var(--app-bg);
}

.login-card {
  width: 100%;
  max-width: 380px;
  padding: 28px 24px;
}

.logo {
  text-align: center;
  margin-bottom: 20px;
}

.logo img {
  width: 52px;
  height: 52px;
}

.logo h1 {
  margin: 10px 0 4px;
  font-size: 22px;
}

.logo p {
  margin: 0;
  font-size: 13px;
}

.hint {
  text-align: center;
  font-size: 13px;
  margin: 14px 0 0;
}

.langs {
  display: flex;
  justify-content: center;
  gap: 4px;
  margin-top: 18px;
}

.langs button {
  border: 0;
  background: none;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--app-text-2);
  cursor: pointer;
  border-radius: 4px;
}

.langs button.on {
  color: var(--app-accent);
  font-weight: 600;
}
</style>
