/**
 * 书源登录界面 / 发现页控件的排版，对应 App 中 FlexboxLayout 的 FlexChildStyle：
 *
 * - layout_flexBasisPercent：占一行宽度的比例（0~1），-1 表示按控件本身宽度
 * - layout_flexGrow：一行有剩余空间时的放大比例，默认 0（不放大）
 * - layout_flexShrink：空间不足时的缩小比例，默认 1
 * - layout_alignSelf：auto / flex_start / flex_end / center / baseline / stretch
 * - layout_wrapBefore：在此控件前强制换行（模板中插入 .flex-break 实现）
 * - layout_justifySelf：控件内部文字的水平对齐
 *
 * App 中输入框、下拉框默认宽度是 match_parent（独占一行），按钮默认按文字宽度。
 */

export interface FlexChildStyle {
  layout_flexGrow?: number
  layout_flexShrink?: number
  layout_alignSelf?: string
  layout_flexBasisPercent?: number
  layout_wrapBefore?: boolean
  layout_justifySelf?: string
}

/** 与容器 CSS 中 gap 保持一致 */
export const FLEX_GAP = 8

const ALIGN: Record<string, string> = {
  flex_start: 'flex-start',
  flex_end: 'flex-end',
  center: 'center',
  baseline: 'baseline',
  stretch: 'stretch',
}

const JUSTIFY: Record<string, string> = {
  flex_start: 'flex-start',
  flex_end: 'flex-end',
  center: 'center',
}

/**
 * @param fullWidth 没有设置 flexBasisPercent 时是否独占一行（App 中 match_parent 的控件）
 */
export function flexItemStyle(s: FlexChildStyle | null | undefined, fullWidth: boolean): Record<string, string> {
  const grow = s?.layout_flexGrow ?? 0
  const shrink = s?.layout_flexShrink ?? 1
  const p = s?.layout_flexBasisPercent ?? -1
  let basis: string
  if (p > 0) {
    // 扣掉间距：同一行 n 个 1/n 的控件加上间距刚好占满一行
    basis = p >= 1 ? '100%' : `calc(${+(p * 100).toFixed(4)}% - ${+(FLEX_GAP * (1 - p)).toFixed(2)}px)`
  } else {
    basis = fullWidth ? '100%' : 'auto'
  }
  const style: Record<string, string> = { flex: `${grow} ${shrink} ${basis}`, maxWidth: '100%' }
  const align = s?.layout_alignSelf && ALIGN[s.layout_alignSelf]
  if (align) style.alignSelf = align
  const justify = s?.layout_justifySelf && JUSTIFY[s.layout_justifySelf]
  if (justify) {
    style.justifyContent = justify
    style.textAlign = justify === 'flex-start' ? 'left' : justify === 'flex-end' ? 'right' : 'center'
  }
  return style
}
