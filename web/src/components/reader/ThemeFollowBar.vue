<script setup lang="ts">
import { t } from '@/i18n'

/** 系统切换深浅色模式后，询问是否切换正文主题（见 useThemeFollow） */
defineProps<{ dark: boolean }>()
defineEmits<{ switch: []; always: []; never: []; close: [] }>()
</script>

<template>
  <div class="follow-bar" role="status" @click.stop>
    <div class="msg">
      {{ dark ? t('系统已切换为深色模式，要换成深色的正文主题吗？') : t('系统已切换为浅色模式，要换成浅色的正文主题吗？') }}
      <button class="close" :aria-label="t('关闭')" @click="$emit('close')">×</button>
    </div>
    <div class="actions">
      <button class="primary" @click="$emit('switch')">{{ t('切换') }}</button>
      <button @click="$emit('always')">{{ t('总是自动切换') }}</button>
      <button @click="$emit('never')">{{ t('不再提示') }}</button>
    </div>
  </div>
</template>

<style scoped>
.follow-bar {
  position: fixed;
  left: 50%;
  bottom: calc(24px + env(safe-area-inset-bottom));
  z-index: 30;
  transform: translateX(-50%);
  width: max-content;
  max-width: calc(100vw - 32px);
  border-radius: 10px;
  background: rgba(32, 32, 32, 0.92);
  color: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  font-size: 14px;
  overflow: hidden;
}

.msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 8px 8px 14px;
  line-height: 1.5;
}

.close {
  flex-shrink: 0;
  margin-left: auto;
  padding: 0 6px;
  border: none;
  background: none;
  color: rgba(255, 255, 255, 0.6);
  font-size: 18px;
  line-height: 1.2;
  cursor: pointer;
}

.actions {
  display: flex;
  border-top: 1px solid rgba(255, 255, 255, 0.15);
}

.actions button {
  flex: 1;
  padding: 9px 12px;
  border: none;
  background: none;
  color: #fff;
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
}

.actions button + button {
  border-left: 1px solid rgba(255, 255, 255, 0.15);
}

.actions button:hover {
  background: rgba(255, 255, 255, 0.12);
}

.actions .primary {
  color: #e0b374;
  font-weight: 600;
}
</style>
