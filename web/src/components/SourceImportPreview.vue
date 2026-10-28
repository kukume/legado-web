<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NCheckbox, NInput, NTag, NVirtualList } from 'naive-ui'
import type { ImportPreviewItem } from '@/api'
import { t } from '@/i18n'

/**
 * 导入前勾选书源（与 App 的导入弹窗一致）：显示新增 / 更新 / 已有，默认全选
 */
const props = defineProps<{ items: ImportPreviewItem[] }>()
/** 选中的下标 */
const selected = defineModel<Set<number>>('selected', { required: true })

const keyword = ref('')

const statusTag = {
  new: { type: 'success', label: () => t('新增') },
  update: { type: 'warning', label: () => t('更新') },
  exists: { type: 'default', label: () => t('已有') },
} as const

const tagOf = (item: ImportPreviewItem) => statusTag[item.status]

const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  const all = props.items.map((item, index) => ({ item, index }))
  if (!k) return all
  return all.filter(({ item }) =>
    item.name.toLowerCase().includes(k) || item.url.toLowerCase().includes(k) || (item.group ?? '').toLowerCase().includes(k),
  )
})

const counts = computed(() => {
  const c = { new: 0, update: 0, exists: 0 }
  props.items.forEach((i) => c[i.status]++)
  return c
})

const allChecked = computed(() => rows.value.length > 0 && rows.value.every((r) => selected.value.has(r.index)))
const someChecked = computed(() => !allChecked.value && rows.value.some((r) => selected.value.has(r.index)))

function update(indices: number[], on: boolean) {
  const next = new Set(selected.value)
  indices.forEach((i) => (on ? next.add(i) : next.delete(i)))
  selected.value = next
}

function toggleAll(on: boolean) {
  update(rows.value.map((r) => r.index), on)
}

/** 只选新增和更新的（跳过已有） */
function selectChanged() {
  selected.value = new Set(props.items.flatMap((item, i) => (item.status === 'exists' ? [] : [i])))
}
</script>

<template>
  <div class="preview">
    <div class="preview-bar">
      <n-checkbox :checked="allChecked" :indeterminate="someChecked" @update:checked="toggleAll">
        {{ t('全选') }}
      </n-checkbox>
      <span class="muted small">
        {{ t('已选 {0}/{1}', [selected.size, items.length]) }} ·
        {{ t('新增 {0} · 更新 {1} · 已有 {2}', [counts.new, counts.update, counts.exists]) }}
      </span>
      <n-button v-if="counts.exists" size="tiny" quaternary type="primary" @click="selectChanged">
        {{ t('只选新增和更新') }}
      </n-button>
    </div>
    <n-input v-model:value="keyword" size="small" clearable :placeholder="t('筛选名称、地址、分组')" />
    <n-virtual-list class="preview-list" :items="rows" :item-size="50" key-field="index">
      <template #default="{ item: row }">
        <div class="preview-row" @click="update([row.index], !selected.has(row.index))">
          <n-checkbox class="row-check" :checked="selected.has(row.index)" />
          <div class="preview-text">
            <div class="ellipsis">{{ row.item.name }}</div>
            <div class="muted small ellipsis">
              <template v-if="row.item.group">{{ row.item.group }} · </template>{{ row.item.url }}
            </div>
          </div>
          <n-tag size="small" :bordered="false" :type="tagOf(row.item).type">
            {{ tagOf(row.item).label() }}
          </n-tag>
        </div>
      </template>
    </n-virtual-list>
  </div>
</template>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preview-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.preview-list {
  max-height: 50vh;
  border-top: 1px solid var(--app-border);
}

.preview-row {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 50px;
  padding: 0 2px;
  border-bottom: 1px solid var(--app-border);
  cursor: pointer;
}

/* 整行可点，复选框只用来显示状态 */
.row-check {
  pointer-events: none;
}

.preview-text {
  flex: 1;
  min-width: 0;
  line-height: 1.35;
}

.small {
  font-size: 12.5px;
}
</style>
