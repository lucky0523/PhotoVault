/// <reference types="vite/client" />
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/** Web build version, injected by Vite from package.json (see vite.config.ts). */
declare const __APP_VERSION__: string
