import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getSetupStatus } from '@/api/setup'

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
    path: '/',
    component: () => import('@/views/MainLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/photos',
    children: [
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
        path: 'devices',
        name: 'Devices',
        component: () => import('@/views/DevicesView.vue'),
      },
      {
        path: 'trash',
        name: 'Trash',
        component: () => import('@/views/TrashView.vue'),
      },
      {
        path: 'admin/users',
        name: 'AdminUsers',
        component: () => import('@/views/AdminUsersView.vue'),
        meta: { requiresAdmin: true },
      },
    ],
  },
]

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

  // If already authenticated and going to login, redirect to photos
  if (to.name === 'Login' && authStore.isAuthenticated) {
    next({ name: 'Photos' })
    return
  }

  next()
})

export default router
