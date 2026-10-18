<script setup lang="ts">
/**
 * 阅读快捷键设置：点“添加”后按下想要的按键
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { NButton, NModal, NTag, useMessage } from 'naive-ui'
import {
  defaultKeyBindings, eventToKey, findAction, keyLabel, readerActions, type KeyBindings, type ReaderAction,
} from '@/utils/keys'
import { t } from '@/i18n'

const props = defineProps<{ show: boolean; value: KeyBindings }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'update:value', v: KeyBindings): void }>()
const message = useMessage()

/** 正在录入按键的动作 */
const capturing = ref<ReaderAction | null>(null)

function update(v: KeyBindings) {
  emit('update:value', v)
}

function remove(action: ReaderAction, key: string) {
  update({ ...props.value, [action]: props.value[action].filter((k) => k !== key) })
}

function onCapture(e: KeyboardEvent) {
  if (!capturing.value) return
  // 录入时拦截所有按键，不触发阅读页的快捷键
  e.preventDefault()
  e.stopImmediatePropagation()
  if (e.key === 'Escape') {
    capturing.value = null
    return
  }
  const key = eventToKey(e)
  if (!key) return // 只按了修饰键，继续等待
  const action = capturing.value
  capturing.value = null
  const next: KeyBindings = { ...props.value }
  const owner = findAction(next, key)
  if (owner === action) return
  if (owner) {
    // 同一个按键只能对应一个动作
    next[owner] = next[owner].filter((k) => k !== key)
    message.info(t('「{0}」已从「{1}」移到这里', [keyLabel(key), readerActions.find((a) => a.key === owner)?.label]))
  }
  next[action] = [...next[action], key]
  update(next)
}

watch(capturing, (v) => {
  if (v) window.addEventListener('keydown', onCapture, true)
  else window.removeEventListener('keydown', onCapture, true)
})
watch(
  () => props.show,
  (v) => {
    if (!v) capturing.value = null
  },
)
onBeforeUnmount(() => window.removeEventListener('keydown', onCapture, true))

function resetAll() {
  update(defaultKeyBindings())
  message.success(t('已恢复默认快捷键'))
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="t('快捷键')"
    style="max-width: 520px"
    :mask-closable="!capturing"
    :close-on-esc="!capturing"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="rows">
      <div v-for="a in readerActions" :key="a.key" class="row">
        <div class="label">{{ a.label }}</div>
        <div class="keys">
          <n-tag v-for="k in value[a.key]" :key="k" size="small" closable @close="remove(a.key, k)">{{ keyLabel(k) }}</n-tag>
          <n-button
            size="tiny"
            :type="capturing === a.key ? 'primary' : 'default'"
            dashed
            @click="capturing = capturing === a.key ? null : a.key"
          >
            {{ capturing === a.key ? t('请按下按键…（Esc 取消）') : t('+ 添加') }}
          </n-button>
        </div>
      </div>
    </div>
    <p class="muted hint">
      {{ t('可以组合 Ctrl / Alt / Shift。上下滚动模式下，↑ ↓ 保持浏览器原本的滚动，不会整页翻动。') }}
    </p>
    <template #footer>
      <div class="foot">
        <n-button size="small" @click="resetAll">{{ t('恢复默认') }}</n-button>
        <n-button size="small" type="primary" @click="emit('update:show', false)">{{ t('完成') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.label {
  width: 72px;
  flex-shrink: 0;
  font-size: 14px;
  line-height: 24px;
}

.keys {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-height: 24px;
}

.hint {
  font-size: 12px;
  margin: 14px 0 0;
}

.foot {
  display: flex;
  justify-content: space-between;
}
</style>
