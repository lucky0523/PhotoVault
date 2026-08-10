import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
// PhotoVault 主题色，须在 Element 默认样式之后引入以覆盖其 CSS 变量
import './styles/theme.css'
// PhotoVault 布局规范（间距 / 圆角 / 分隔线 / 页面骨架 / 照片瓦片），依赖 theme.css 的颜色变量
import './styles/layout.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// Register all Element Plus icons globally
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.mount('#app')
