/// <reference types="vite/client" />
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/** Web build version, injected by Vite from package.json (see vite.config.ts). */
declare const __APP_VERSION__: string

/**
 * 本次构建是否面向飞牛（fnOS）应用包，由 Vite 的 define 替换成字面量
 * true / false（见 vite.config.ts）。
 *
 * 因为是字面量而不是变量，`if (__FNOS_BUILD__) { ... }` 在普通构建里会被
 * Rollup 当作死代码整块删除，块内动态 import 的页面不会进入 dist。
 */
declare const __FNOS_BUILD__: boolean
