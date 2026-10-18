import { onBeforeUnmount, ref } from 'vue'

/** 手机宽度（与布局的断点一致：767px 以下显示底部导航） */
export function useIsMobile() {
  const mq = typeof window !== 'undefined' ? window.matchMedia('(max-width: 767px)') : null
  const isMobile = ref(!!mq?.matches)
  const update = () => (isMobile.value = !!mq?.matches)
  mq?.addEventListener('change', update)
  onBeforeUnmount(() => mq?.removeEventListener('change', update))
  return isMobile
}
