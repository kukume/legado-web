import { computed, h, type Ref, type VNodeChild } from 'vue'
import { NButton, NDropdown, NIcon, NSwitch, type DataTableColumns } from 'naive-ui'
import { EllipsisVertical } from '@vicons/ionicons5'

export interface RowAction {
  label: string
  key: string
  /** 删除等危险操作显示为红色 */
  danger?: boolean
  onSelect: () => void
}

/**
 * 手机上的列表列（与 App 一样）：多选 | 名称（下面一行小字） | 启用开关 | ⋮ 菜单。
 * 不需要横向滑动就能看到所有操作
 */
export function mobileColumns<T>(o: {
  name: (row: T) => VNodeChild
  sub?: (row: T) => VNodeChild
  enabled: (row: T) => boolean
  onToggle: (row: T, value: boolean) => void
  actions: (row: T) => RowAction[]
}): DataTableColumns<T> {
  return [
    { type: 'selection', width: 36 },
    {
      key: 'name',
      title: '',
      render: (row) =>
        h('div', { class: 'm-row' }, [
          h('div', { class: 'm-name' }, [o.name(row)]),
          o.sub ? h('div', { class: 'm-sub' }, [o.sub(row)]) : null,
        ]),
    },
    {
      key: 'enabled',
      title: '',
      width: 52,
      render: (row) => h(NSwitch, { size: 'small', value: o.enabled(row), onUpdateValue: (v: boolean) => o.onToggle(row, v) }),
    },
    {
      key: 'more',
      title: '',
      width: 36,
      render: (row) => {
        const actions = o.actions(row)
        return h(
          NDropdown,
          {
            trigger: 'click',
            placement: 'bottom-end',
            options: actions.map((a) => ({ label: a.label, key: a.key, props: a.danger ? { style: 'color: #d03050' } : undefined })),
            onSelect: (key: string) => actions.find((a) => a.key === key)?.onSelect(),
          },
          () => h(NButton, { size: 'tiny', quaternary: true, circle: true }, { icon: () => h(NIcon, null, () => h(EllipsisVertical)) }),
        )
      },
    },
  ]
}

/**
 * 手机上隐藏了表头（只剩一个全选框很难看），全选放到工具栏里：rows 为当前列表，checked 为选中的行
 */
export function useSelectAll<T, K extends string | number>(rows: Readonly<Ref<T[]>>, checked: Ref<K[]>, key: (row: T) => K) {
  const all = computed(() => rows.value.length > 0 && rows.value.every((r) => checked.value.includes(key(r))))
  const some = computed(() => !all.value && checked.value.length > 0)
  function toggleAll(v: boolean) {
    checked.value = v ? rows.value.map(key) : []
  }
  return { all, some, toggleAll }
}
