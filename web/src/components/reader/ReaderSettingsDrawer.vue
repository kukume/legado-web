<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NDrawer, NDrawerContent, NRadioButton, NRadioGroup, NSelect, NSlider, NSwitch, useDialog, useMessage } from 'naive-ui'
import { customFontId, customThemeKey, readerFonts, readerThemes, useReaderStore, type CustomTheme, type PageAnim } from '@/stores/reader'
import { fontApi, type UserFont } from '@/api'
import { wakeLockSupported } from '@/composables/useWakeLock'
import { isEn, t } from '@/i18n'

/** 阅读设置（底部抽屉）：直接修改阅读设置 store，修改会同步到其他设备 */
defineProps<{
  isComic: boolean
  isPaged: boolean
  /** 实际使用的翻页动画（设置里的值无效时为平移） */
  pageAnim: PageAnim
  autoRunning: boolean
  autoLabel: string
  /** 本书的正文处理（漫画、PDF 等没有文字的书为 null） */
  processing: { reSegment: boolean; removeSameTitle: boolean; useReplaceRule: boolean } | null
}>()
const show = defineModel<boolean>('show', { default: false })
defineEmits<{
  startAuto: []
  stopAuto: []
  keys: []
  newTheme: []
  editTheme: [theme: CustomTheme]
  process: [key: 'reSegment' | 'removeSameTitle' | 'useReplaceRule', value: boolean]
}>()

const readerStore = useReaderStore()
const cfg = computed(() => readerStore.config)
const customThemes = computed(() => cfg.value.customThemes ?? [])

// ---------------- 字体：预设字体 + 用户上传的字体 ----------------

const message = useMessage()
const dialog = useDialog()
const userFonts = ref<UserFont[]>([])
const fontsLoaded = ref(false)
const uploading = ref(false)
const fontInput = ref<HTMLInputElement | null>(null)

// 第一次打开设置时读取上传的字体
watch(show, async (v) => {
  if (!v || fontsLoaded.value) return
  try {
    userFonts.value = await fontApi.list()
    fontsLoaded.value = true
  } catch {
    /* 读取失败时只显示预设字体 */
  }
})

const fontOptions = computed(() => [
  ...readerFonts,
  ...userFonts.value.map((f) => ({ label: f.name, value: `custom:${f.id}` })),
  // 设置里是已删除的字体时也显示一项，避免下拉框显示成一串 id
  ...(customFontId(cfg.value.fontFamily) && !userFonts.value.some((f) => `custom:${f.id}` === cfg.value.fontFamily)
    ? [{ label: t('（字体已删除）'), value: cfg.value.fontFamily }]
    : []),
])
const currentFont = computed(() => userFonts.value.find((f) => `custom:${f.id}` === cfg.value.fontFamily) ?? null)

async function onFontFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploading.value = true
  try {
    const f = await fontApi.upload(file)
    userFonts.value.push(f)
    cfg.value.fontFamily = `custom:${f.id}`
    message.success(t('已添加字体「{0}」', [f.name]))
  } catch (err: any) {
    message.error(err.message)
  } finally {
    uploading.value = false
  }
}

function removeFont(f: UserFont) {
  dialog.warning({
    title: t('删除字体'),
    content: t('确定删除字体「{0}」？使用它的设备会改回系统默认字体。', [f.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await fontApi.remove(f.id)
      userFonts.value = userFonts.value.filter((x) => x.id !== f.id)
      if (cfg.value.fontFamily === `custom:${f.id}`) cfg.value.fontFamily = 'system'
    },
  })
}
</script>

<template>
  <!-- 不自动聚焦：否则第一个选项（上下滚动）会带上焦点描边，看起来像被选中了 -->
  <n-drawer v-model:show="show" :height="420" placement="bottom" :auto-focus="false">
    <n-drawer-content :title="t('阅读设置')">
      <div class="settings" :class="{ en: isEn }">
        <div class="set-row">
          <label>{{ t('主题') }}</label>
          <div class="themes">
            <button
              v-for="th in readerThemes"
              :key="th.key"
              class="theme-btn"
              :class="{ active: cfg.theme === th.key }"
              :style="{ background: th.bg, color: th.text }"
              @click="cfg.theme = th.key"
            >{{ th.name }}</button>
            <!-- 自定义主题：选中后再点一次可以编辑 -->
            <button
              v-for="c in customThemes"
              :key="c.id"
              class="theme-btn"
              :class="{ active: cfg.theme === customThemeKey(c.id) }"
              :style="{ background: c.bg, color: c.text }"
              :title="cfg.theme === customThemeKey(c.id) ? t('再点一次编辑') : c.name"
              @click="cfg.theme === customThemeKey(c.id) ? $emit('editTheme', c) : (cfg.theme = customThemeKey(c.id))"
            >{{ c.name }}</button>
            <button v-if="customThemes.length < 10" class="theme-btn theme-add" :title="t('自定义主题')" @click="$emit('newTheme')">{{ t('＋ 自定义') }}</button>
          </div>
        </div>
        <div class="set-row">
          <label>{{ t('翻页方式') }}</label>
          <span v-if="isComic" class="muted-r small">{{ t('漫画和 PDF 固定为上下滚动') }}</span>
          <n-radio-group v-else v-model:value="cfg.mode" size="small">
            <n-radio-button value="scroll">{{ t('上下滚动') }}</n-radio-button>
            <n-radio-button value="page">{{ t('左右翻页') }}</n-radio-button>
            <n-radio-button value="vpage">{{ t('上下翻页') }}</n-radio-button>
          </n-radio-group>
        </div>
        <div v-if="cfg.mode === 'page' && !isComic" class="set-row">
          <label>{{ t('双页显示') }}</label>
          <n-radio-group :value="cfg.doublePage ?? 'auto'" size="small" @update:value="(v: 'auto' | 'off') => (cfg.doublePage = v)">
            <n-radio-button value="auto">{{ t('宽屏时双页') }}</n-radio-button>
            <n-radio-button value="off">{{ t('始终单页') }}</n-radio-button>
          </n-radio-group>
        </div>
        <div v-if="isPaged" class="set-row">
          <label>{{ t('翻页动画') }}</label>
          <n-radio-group :value="pageAnim" size="small" @update:value="(v: PageAnim) => (cfg.pageAnim = v)">
            <n-radio-button value="slide">{{ t('平移') }}</n-radio-button>
            <n-radio-button value="cover">{{ t('覆盖') }}</n-radio-button>
            <n-radio-button value="curl">{{ t('仿真') }}</n-radio-button>
            <n-radio-button value="fade">{{ t('淡入淡出') }}</n-radio-button>
            <n-radio-button value="none">{{ t('无') }}</n-radio-button>
          </n-radio-group>
        </div>
        <div class="set-row">
          <label>{{ t('字体') }}</label>
          <n-select v-model:value="cfg.fontFamily" :options="fontOptions" size="small" style="width: 160px" />
          <n-button size="small" :loading="uploading" @click="fontInput?.click()">{{ t('上传字体') }}</n-button>
          <n-button v-if="currentFont" size="small" type="error" ghost @click="removeFont(currentFont)">{{ t('删除') }}</n-button>
          <input ref="fontInput" type="file" accept=".ttf,.otf,.woff,.woff2,.ttc,font/*" hidden @change="onFontFile" />
        </div>
        <div v-if="!isComic" class="set-row">
          <label>{{ t('自动阅读') }}</label>
          <n-button size="small" :type="autoRunning ? 'warning' : 'primary'" @click="autoRunning ? $emit('stopAuto') : $emit('startAuto')">
            {{ autoRunning ? t('停止') : t('开始') }}
          </n-button>
          <n-slider v-if="isPaged" v-model:value="cfg.autoPageSeconds" :min="3" :max="60" :step="1" :tooltip="false" style="flex: 1" />
          <n-slider v-else v-model:value="cfg.autoScrollSpeed" :min="10" :max="200" :step="5" :tooltip="false" style="flex: 1" />
          <span class="muted-r small auto-val">{{ autoLabel }}</span>
        </div>
        <div class="set-row">
          <label>{{ t('字号 {0}', [cfg.fontSize]) }}</label>
          <n-slider v-model:value="cfg.fontSize" :min="12" :max="40" :step="1" />
        </div>
        <div class="set-row">
          <label>{{ t('字重 {0}', [cfg.fontWeight]) }}</label>
          <n-slider v-model:value="cfg.fontWeight" :min="300" :max="700" :step="100" />
        </div>
        <div class="set-row">
          <label>{{ t('行距 {0}', [cfg.lineHeight.toFixed(1)]) }}</label>
          <n-slider v-model:value="cfg.lineHeight" :min="1.2" :max="3" :step="0.1" />
        </div>
        <div class="set-row">
          <label>{{ t('段距 {0}', [cfg.paragraphSpacing.toFixed(1)]) }}</label>
          <n-slider v-model:value="cfg.paragraphSpacing" :min="0" :max="2" :step="0.1" />
        </div>
        <div class="set-row">
          <label>{{ t('字距 {0}', [cfg.letterSpacing.toFixed(2)]) }}</label>
          <n-slider v-model:value="cfg.letterSpacing" :min="0" :max="0.3" :step="0.01" />
        </div>
        <div class="set-row">
          <label>{{ t('页宽 {0}', [cfg.width]) }}</label>
          <n-slider v-model:value="cfg.width" :min="400" :max="1400" :step="10" />
        </div>
        <div class="set-row">
          <label>{{ t('首行缩进') }}</label>
          <n-switch v-model:value="cfg.indent" />
          <label style="margin-left: 24px">{{ t('点击任意位置都向后翻') }}</label>
          <n-switch v-model:value="cfg.tapNextOnLeft" />
        </div>
        <template v-if="!isComic">
          <div class="set-row">
            <label>{{ t('标题') }}</label>
            <n-radio-group :value="cfg.titleAlign ?? 'left'" size="small" @update:value="(v: 'left' | 'center' | 'hidden') => (cfg.titleAlign = v)">
              <n-radio-button value="left">{{ t('居左') }}</n-radio-button>
              <n-radio-button value="center">{{ t('居中') }}</n-radio-button>
              <n-radio-button value="hidden">{{ t('隐藏') }}</n-radio-button>
            </n-radio-group>
          </div>
          <template v-if="cfg.titleAlign !== 'hidden'">
            <div class="set-row">
              <label>{{ t('标题字号 {0}', [(cfg.titleSize ?? 1.4).toFixed(1)]) }}</label>
              <n-slider :value="cfg.titleSize ?? 1.4" :min="1" :max="2.4" :step="0.1" @update:value="(v: number) => (cfg.titleSize = v)" />
            </div>
            <div class="set-row">
              <label>{{ t('上间距 {0}', [(cfg.titleTop ?? 0.4).toFixed(1)]) }}</label>
              <n-slider :value="cfg.titleTop ?? 0.4" :min="0" :max="4" :step="0.1" @update:value="(v: number) => (cfg.titleTop = v)" />
            </div>
            <div class="set-row">
              <label>{{ t('下间距 {0}', [(cfg.titleBottom ?? 1.2).toFixed(1)]) }}</label>
              <n-slider :value="cfg.titleBottom ?? 1.2" :min="0" :max="4" :step="0.1" @update:value="(v: number) => (cfg.titleBottom = v)" />
            </div>
          </template>
        </template>
        <template v-if="processing">
          <div class="set-row">
            <label>{{ t('替换净化') }}</label>
            <n-switch :value="processing.useReplaceRule" @update:value="(v: boolean) => $emit('process', 'useReplaceRule', v)" />
            <span class="muted-r small">{{ t('本书使用替换规则（去广告、改错字等），关闭后显示原文') }}</span>
          </div>
          <div class="set-row">
            <label>{{ t('删除重复标题') }}</label>
            <n-switch :value="processing.removeSameTitle" @update:value="(v: boolean) => $emit('process', 'removeSameTitle', v)" />
            <span class="muted-r small">{{ t('本书正文开头与章节名相同的一行不显示') }}</span>
          </div>
          <div class="set-row">
            <label>{{ t('重新分段') }}</label>
            <n-switch :value="processing.reSegment" @update:value="(v: boolean) => $emit('process', 'reSegment', v)" />
            <span class="muted-r small">{{ t('本书段落错乱（一句一行或整章一段）时，按标点和对话重新分段') }}</span>
          </div>
        </template>
        <div class="set-row">
          <label>{{ t('屏幕常亮') }}</label>
          <n-switch :value="cfg.keepScreenOn !== false" :disabled="!wakeLockSupported" @update:value="(v: boolean) => (cfg.keepScreenOn = v)" />
          <span v-if="!wakeLockSupported" class="muted-r small" style="margin-left: 8px">{{ t('当前浏览器不支持（需要 HTTPS）') }}</span>
        </div>
        <div class="set-row">
          <label>{{ t('快捷键') }}</label>
          <n-button size="small" @click="$emit('keys')">{{ t('自定义') }}</n-button>
        </div>
        <div class="set-row">
          <n-button size="small" @click="readerStore.reset()">{{ t('恢复默认') }}</n-button>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 720px;
  margin: 0 auto;
}

.set-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.set-row > label {
  width: 84px;
  flex-shrink: 0;
  font-size: 13px;
}

/* 英文标签更长（如 Paragraph spacing） */
.settings.en .set-row > label {
  width: 132px;
}

.themes {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.theme-btn {
  border: 2px solid transparent;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.3);
}

.theme-btn.active {
  border-color: var(--app-accent);
}

.theme-add {
  background: transparent;
  color: inherit;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.45) inset;
  border-style: dashed;
  border-color: transparent;
}

.muted-r {
  opacity: 0.6;
}

.small {
  font-size: 12px;
}

.auto-val {
  width: 76px;
  flex-shrink: 0;
  text-align: right;
}
</style>
