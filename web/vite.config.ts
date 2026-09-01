import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'fs'
import { resolve } from 'path'

// package.json is read at config time (instead of imported) so tsconfig.node
// doesn't need resolveJsonModule. Exposed to the app as __APP_VERSION__ so the
// 关于服务端 page can show the Web build version next to the server version.
const pkg = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf-8')
) as { version: string }

// 飞牛（fnOS）专属功能的编译期开关，由 scripts/build.sh 的 fpk 目标设置
// PHOTOVAULT_FNOS_BUILD=1 打开。
//
// 之所以用 define 而不是 import.meta.env：define 会把标识符替换成字面量
// true / false，`if (__FNOS_BUILD__) { ... }` 因此在非飞牛构建里是死代码，
// Rollup 会整块删掉。块内的 `() => import('@/views/FnosSharedAccessView.vue')`
// 随之不可达，对应的 lazy chunk 根本不会产出——这正是「只在编译飞牛应用时
// 才打包进去」的实现方式。改用运行时判断（env 变量、接口探测）做不到这一点，
// 页面代码仍会留在 dist 里。
const isFnosBuild = process.env.PHOTOVAULT_FNOS_BUILD === '1'

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __FNOS_BUILD__: JSON.stringify(isFnosBuild),
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
    },
  },
})
