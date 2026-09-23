import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'fs'
import { resolve } from 'path'

// 产品版本号：从 server/app/__init__.py 的 __version__ 读，那是全仓库唯一的修改
// 入口（原因见该文件的说明）。以 __APP_VERSION__ 注入，「关于服务端」页面用它显示
// 「Web 端版本」。
//
// 之前这里读的是 web/package.json，于是 Web 和服务端各有一份版本号，页面上并排显示
// 两个不同的数字——但它们本就是同一次发布：server/app/main.py 会把 web/dist 挂到
// 根路径上托管，Docker 镜像和 .fpk 也都是把两者打进同一个包。
//
// 读不到就直接失败，不做静默回落：一个假版本号比构建失败更难发现。
function readProductVersion(): string {
  const versionFile = resolve(__dirname, '../server/app/__init__.py')
  const source = readFileSync(versionFile, 'utf-8')
  const match = source.match(/^__version__\s*=\s*"([^"]+)"/m)
  if (!match) {
    throw new Error(`在 ${versionFile} 里找不到 __version__，无法确定产品版本号`)
  }
  return match[1]
}

const productVersion = readProductVersion()

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
    __APP_VERSION__: JSON.stringify(productVersion),
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
