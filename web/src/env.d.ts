/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/vue" />
/// <reference types="vite-plugin-pwa/info" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, any>
  export default component
}

import type { t } from '@/i18n'
declare module 'vue' {
  interface ComponentCustomProperties {
    /** 界面翻译，见 src/i18n */
    t: typeof t
  }
}

import 'vue-router'
declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    admin?: boolean
    title?: string
  }
}
