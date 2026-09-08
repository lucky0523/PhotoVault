import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getSetupStatus } from '@/api/setup'

// 主布局（MainLayout）下的二级页面。
//
// 单独抽成变量而不是内联进 routes：文件末尾的飞牛专属入口需要往这里 push 一项，
// 而那段代码必须能被整块删除（见下面 __FNOS_BUILD__ 处的说明）。
const mainChildren: RouteRecordRaw[] = [
  {
    path: 'photos',
    name: 'Photos',
    component: () => import('@/views/PhotosView.vue'),
  },
  {
    path: 'timeline',
    name: 'Timeline',
    component: () => import('@/views/TimelineView.vue'),
  },
  {
    path: 'explore',
    name: 'Explore',
    component: () => import('@/views/ExploreView.vue'),
  },
  {
    path: 'explore/manage',
    name: 'ExploreManage',
    component: () => import('@/views/ExploreManageView.vue'),
  },
  {
    path: 'explore/people/:id',
    name: 'ExplorePeople',
    component: () => import('@/views/CategoryPhotosView.vue'),
  },
  {
    path: 'explore/places/:city',
    name: 'ExplorePlaces',
    component: () => import('@/views/CategoryPhotosView.vue'),
  },
  {
    path: 'explore/scenes/:label',
    name: 'ExploreScenes',
    component: () => import('@/views/CategoryPhotosView.vue'),
  },
  {
    path: 'trash',
    name: 'Trash',
    component: () => import('@/views/TrashView.vue'),
  },

  // --- 设置 ---------------------------------------------------------
  // 设备管理与用户管理原本是侧边栏一级入口（/devices、/admin/users），
  // 现在收敛到「设置」分组下。旧路径保留为重定向，这样已被收藏或写进
  // 文档的链接不会失效。
  {
    path: 'settings',
    redirect: { name: 'Devices' },
  },
  {
    path: 'settings/devices',
    name: 'Devices',
    component: () => import('@/views/DevicesView.vue'),
  },
  {
    path: 'settings/users',
    name: 'AdminUsers',
    component: () => import('@/views/AdminUsersView.vue'),
  },
  {
    path: 'settings/client-versions',
    name: 'ClientVersions',
    component: () => import('@/views/ClientVersionsView.vue'),
    meta: { requiresAdmin: true },
  },
  {
    path: 'settings/about',
    name: 'AboutServer',
    component: () => import('@/views/AboutServerView.vue'),
  },
  { path: 'devices', redirect: { name: 'Devices' } },
  { path: 'admin/users', redirect: { name: 'AdminUsers' } },
]

const routes: RouteRecordRaw[] = [
  {
    path: '/setup',
    name: 'Setup',
    component: () => import('@/views/SetupView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/views/MainLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/photos',
    children: mainChildren,
  },
]

// --- 飞牛（fnOS）专属页面 ---------------------------------------------------
// __FNOS_BUILD__ 由 Vite 的 define 替换成字面量 true / false（见 vite.config.ts）。
// 普通构建里这里就是 `if (false) { ... }`，整块会被删掉，块内两个动态 import 因此
// 不可达，对应的 lazy chunk 根本不会产出 —— 这就是「只在编译飞牛应用时才打包
// 进去」的落点。所以这两个页面必须在这个块里内联声明，不能提到模块顶层 import，
// 否则静态依赖会把它们重新拉回普通构建的产物。
if (__FNOS_BUILD__) {
  mainChildren.push({
    path: 'settings/fnos-shared-access',
    name: 'FnosSharedAccess',
    component: () => import('@/views/FnosSharedAccessView.vue'),
    // 这是运维诊断页，且飞牛侧的共享授权本身只允许管理员操作，所以按
    // PhotoVault 管理员收口，避免普通用户点进来只能看到一串 access_denied。
    meta: { requiresAdmin: true },
  })

  // 授权回调页：openAppAuth 的 redirectUri 落点。
  // 必须放在主布局之外并且不要求登录 —— 它是被飞牛授权页跳转过来的独立窗口，
  // 套一层侧边栏没有意义，而要求登录会在回调链路里插入一次重定向，把 URL 上的
  // 授权结果参数丢掉。路径要与 utils/fnos.ts 的 FNOS_AUTH_CALLBACK_PATH 一致。
  routes.push({
    path: '/fnos-auth-callback',
    name: 'FnosAuthCallback',
    component: () => import('@/views/FnosAuthCallbackView.vue'),
    meta: { requiresAuth: false },
  })

  // 错投到本应用 origin 上的飞牛授权路由。
  // /app-auth/* 属于飞牛系统 UI，不是我们的页面。但只要它被拼到了本应用地址上
  // （SDK 在独立页面下按 location.origin 推导基址，就会这样），FastAPI 的 SPA
  // catch-all 会返回 index.html，而这里没有匹配路由 —— 表现是一个纯白页面，
  // 控制台也不报错，完全无从下手。注册一个说明页，把哑失败变成可诊断的失败。
  routes.push({
    path: '/app-auth/:pathMatch(.*)*',
    name: 'FnosAuthMisdirected',
    component: () => import('@/views/FnosAuthMisdirectedView.vue'),
    meta: { requiresAuth: false },
  })
}

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// Cache setup status to avoid re-fetching on every navigation
let setupStatusCache: boolean | null = null

async function checkSetupStatus(): Promise<boolean> {
  if (setupStatusCache !== null) {
    return setupStatusCache
  }
  try {
    const status = await getSetupStatus()
    setupStatusCache = status.initialized
    return setupStatusCache
  } catch {
    // If the endpoint fails, assume initialized to avoid blocking navigation
    return true
  }
}

/**
 * Reset the cached setup status (call after successful initialization)
 */
export function resetSetupStatusCache(): void {
  setupStatusCache = null
}

// Navigation guard: check initialization and authentication
router.beforeEach(async (to, _from, next) => {
  const initialized = await checkSetupStatus()

  // If not initialized, redirect everything to /setup
  if (!initialized && to.name !== 'Setup') {
    next({ name: 'Setup' })
    return
  }

  // If already initialized and trying to access /setup, redirect to /login
  if (initialized && to.name === 'Setup') {
    next({ name: 'Login' })
    return
  }

  const authStore = useAuthStore()

  if (to.meta.requiresAuth !== false && !authStore.isAuthenticated) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
    return
  }

  if (to.meta.requiresAdmin && !authStore.isAdmin) {
    next({ name: 'Photos' })
    return
  }

  // If already authenticated and going to login or register, redirect to photos
  if ((to.name === 'Login' || to.name === 'Register') && authStore.isAuthenticated) {
    next({ name: 'Photos' })
    return
  }

  next()
})

export default router
