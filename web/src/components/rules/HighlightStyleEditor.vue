<script setup lang="ts">
import { computed } from 'vue'
import { NCheckbox, NColorPicker, NSelect, NSlider, NSpace, NSwitch } from 'naive-ui'
import {
  argbToHex, bgSwatches, cssToArgb, presetStyles, styleToCss, textSwatches, type FillShape, type HighlightStyle, type LineKind,
} from '@/utils/highlight'
import { t } from '@/i18n'

/**
 * 高亮样式编辑（与 App 的高亮样式对话框对应）：预设、背景色和形状、字体色、加粗、斜体、下划线、删除线、方框、着重号、阴影、字间距。
 * App 里有、网页版不支持的项（字体、字号、左右边距）不显示，但会原样保留
 */
const model = defineModel<HighlightStyle>({ required: true })

function set(patch: Partial<HighlightStyle>) {
  model.value = { ...model.value, ...patch }
}

const shapeOptions = computed(() =>
  ([
    ['RECTANGLE', '矩形'], ['ROUNDED', '圆角'], ['MARKER', '马克笔'], ['HALF', '半高'], ['BASELINE', '底衬'], ['PILL', '胶囊'],
  ] as [FillShape, string][]).map(([value, label]) => ({ value, label: t(label) })),
)
const lineOptions = computed(() =>
  ([['SOLID', '实线'], ['WAVY', '波浪'], ['DASHED', '虚线'], ['DOTTED', '点线'], ['DOUBLE', '双线']] as [LineKind, string][])
    .map(([value, label]) => ({ value, label: t(label) })),
)

const presetCss = presetStyles.map((s) => styleToCss(s))
const allSwatches = [...bgSwatches, ...textSwatches]

/** 带颜色的开关项（删除线、方框、着重号）：打开时默认用字体色 */
type Deco = 'strike' | 'box' | 'emphasis'
function toggleDeco(k: Deco, on: boolean) {
  set({ [k]: on ? { color: model.value.textColor || (0xffe53935 | 0) } : null })
}
</script>

<template>
  <div class="style-editor">
    <div class="row">
      <span class="label">{{ t('预设') }}</span>
      <div class="presets">
        <button v-for="(p, i) in presetStyles" :key="i" type="button" class="preset" :style="presetCss[i]" @click="model = { ...p }">{{ t('示例') }}</button>
      </div>
    </div>

    <div class="row">
      <span class="label">{{ t('背景色') }}</span>
      <div class="inline grow">
        <n-color-picker class="picker" :value="argbToHex(model.fill)" :swatches="bgSwatches" :modes="['hex']" show-alpha clearable
          @update:value="(v: string | null) => set({ fill: cssToArgb(v) })" />
        <n-select v-if="model.fill" class="shape" size="small" :value="model.fillShape ?? 'RECTANGLE'" :options="shapeOptions"
          @update:value="(v: FillShape) => set({ fillShape: v === 'RECTANGLE' ? null : v })" />
      </div>
    </div>
    <div v-if="model.fill && model.fillShape === 'PILL'" class="row">
      <span class="label">{{ t('弧度') }}</span>
      <n-slider class="grow" :value="Math.round((model.pillPaddingScale ?? 1) * 100)" :min="25" :max="200" :step="5"
        :format-tooltip="(v: number) => `${v}%`" @update:value="(v: number) => set({ pillPaddingScale: v / 100 })" />
    </div>

    <div class="row">
      <span class="label">{{ t('字体色') }}</span>
      <n-color-picker class="picker" :value="argbToHex(model.textColor)" :swatches="textSwatches" :modes="['hex']" show-alpha clearable
        @update:value="(v: string | null) => set({ textColor: cssToArgb(v) })" />
    </div>

    <div class="row">
      <span class="label">{{ t('样式') }}</span>
      <n-space>
        <n-checkbox :checked="!!model.bold" @update:checked="(v: boolean) => set({ bold: v })">{{ t('加粗') }}</n-checkbox>
        <n-checkbox :checked="!!model.italic" @update:checked="(v: boolean) => set({ italic: v })">{{ t('斜体') }}</n-checkbox>
      </n-space>
    </div>

    <div class="row">
      <span class="label">{{ t('下划线') }}</span>
      <div class="inline grow">
        <n-switch :value="!!model.underline" @update:value="(v: boolean) => set({ underline: v ? { kind: 'SOLID', color: model.textColor || (0xff1e88e5 | 0) } : null })" />
        <template v-if="model.underline">
          <n-select class="shape" size="small" :value="model.underline.kind ?? 'SOLID'" :options="lineOptions"
            @update:value="(v: LineKind) => set({ underline: { ...model.underline, kind: v } })" />
          <n-color-picker class="picker small" :value="argbToHex(model.underline.color)" :swatches="allSwatches" :modes="['hex']" show-alpha
            @update:value="(v: string | null) => set({ underline: { ...model.underline, color: cssToArgb(v) } })" />
        </template>
      </div>
    </div>
    <template v-if="model.underline">
      <div class="row">
        <span class="label">{{ t('粗细') }}</span>
        <n-slider class="grow" :value="model.underline.width ?? 1" :min="0.5" :max="10" :step="0.5"
          @update:value="(v: number) => set({ underline: { ...model.underline, width: v } })" />
      </div>
      <div class="row">
        <span class="label">{{ t('距离') }}</span>
        <n-slider class="grow" :value="model.underline.distance ?? 0" :min="0" :max="30" :step="1"
          @update:value="(v: number) => set({ underline: { ...model.underline, distance: v } })" />
      </div>
    </template>

    <div v-for="k in (['strike', 'box', 'emphasis'] as Deco[])" :key="k" class="row">
      <span class="label">{{ k === 'strike' ? t('删除线') : k === 'box' ? t('方框') : t('着重号') }}</span>
      <div class="inline grow">
        <n-switch :value="!!model[k]" @update:value="(v: boolean) => toggleDeco(k, v)" />
        <n-color-picker v-if="model[k]" class="picker small" :value="argbToHex((model[k] as { color?: number }).color)" :swatches="allSwatches" :modes="['hex']" show-alpha
          @update:value="(v: string | null) => set({ [k]: { color: cssToArgb(v) } })" />
      </div>
    </div>

    <div class="row">
      <span class="label">{{ t('阴影 / 发光') }}</span>
      <div class="inline grow">
        <n-switch :value="!!model.shadow" @update:value="(v: boolean) => set({ shadow: v ? { radius: 3, dx: 2, dy: 2, color: 0x80000000 | 0 } : null })" />
        <n-color-picker v-if="model.shadow" class="picker small" :value="argbToHex(model.shadow.color)" :swatches="allSwatches" :modes="['hex']" show-alpha
          @update:value="(v: string | null) => set({ shadow: { ...model.shadow, color: cssToArgb(v) } })" />
      </div>
    </div>
    <template v-if="model.shadow">
      <div class="row">
        <span class="label">{{ t('半径') }}</span>
        <n-slider class="grow" :value="model.shadow.radius ?? 3" :min="0" :max="10" :step="0.5" @update:value="(v: number) => set({ shadow: { ...model.shadow, radius: v } })" />
      </div>
      <div class="row">
        <span class="label">{{ t('横向') }}</span>
        <n-slider class="grow" :value="model.shadow.dx ?? 2" :min="-10" :max="10" :step="0.5" @update:value="(v: number) => set({ shadow: { ...model.shadow, dx: v } })" />
      </div>
      <div class="row">
        <span class="label">{{ t('纵向') }}</span>
        <n-slider class="grow" :value="model.shadow.dy ?? 2" :min="-10" :max="10" :step="0.5" @update:value="(v: number) => set({ shadow: { ...model.shadow, dy: v } })" />
      </div>
    </template>

    <div class="row">
      <span class="label">{{ t('字间距') }}</span>
      <n-slider class="grow" :value="model.letterSpacing ?? 0" :min="-0.5" :max="1" :step="0.05"
        @update:value="(v: number) => set({ letterSpacing: v || null })" />
    </div>
  </div>
</template>

<style scoped>
.style-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 30px;
}

.label {
  flex: 0 0 72px;
  font-size: 13px;
  opacity: 0.8;
}

.grow {
  flex: 1;
  min-width: 0;
}

.inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.style-editor :deep(.picker) {
  flex: 0 0 160px;
  width: 160px;
}

.style-editor :deep(.picker.small) {
  flex-basis: 120px;
  width: 120px;
}

.style-editor :deep(.shape) {
  flex: 0 0 110px;
  width: 110px;
}

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preset {
  font: inherit;
  font-size: 14px;
  padding: 2px 8px;
  border: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.3));
  border-radius: 4px;
  background-color: transparent;
  color: inherit;
  cursor: pointer;
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
</style>
