/**
 * 非 HTTPS（如用局域网 IP 访问）时没有剪贴板接口：用临时文本框选中后执行复制。
 * 手机上点按钮时原来的选区多半已经被取消，不能依赖它
 */
function copyByTextarea(text: string): boolean {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.readOnly = true // 不弹出键盘
  Object.assign(ta.style, { position: 'fixed', top: '0', left: '0', opacity: '0', fontSize: '16px' })
  document.body.appendChild(ta)
  ta.select()
  ta.setSelectionRange(0, text.length) // iOS 需要
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  ta.remove()
  return ok
}

/** 复制文字到剪贴板，返回是否成功 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 退回 execCommand */
  }
  return copyByTextarea(text)
}
