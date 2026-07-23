import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http from '@/api/http'
import router from '@/router'
import { register as apiRegister } from '@/api/auth'

interface UserInfo {
  id: number
  username: string
  is_admin: boolean
}

export const useAuthStore = defineStore('auth', () => {
  // State
  const accessToken = ref<string | null>(localStorage.getItem('access_token'))
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'))
  const userInfo = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('user_info') || 'null')
  )

  // Getters
  const isAuthenticated = computed(() => !!accessToken.value)
  const isAdmin = computed(() => userInfo.value?.is_admin ?? false)
  const username = computed(() => userInfo.value?.username ?? '')

  // Actions
  async function login(usernameInput: string, password: string) {
    const response = await http.post('/auth/login', {
      username: usernameInput,
      password,
    })

    const { access_token, refresh_token } = response.data

    // Store tokens
    accessToken.value = access_token
    refreshToken.value = refresh_token
    localStorage.setItem('access_token', access_token)
    localStorage.setItem('refresh_token', refresh_token)

    // Decode user info from token payload (JWT)
    try {
      const payload = JSON.parse(atob(access_token.split('.')[1]))
      const info: UserInfo = {
        id: payload.sub || payload.user_id,
        username: payload.username || usernameInput,
        is_admin: payload.is_admin || false,
      }
      userInfo.value = info
      localStorage.setItem('user_info', JSON.stringify(info))
    } catch {
      // If token decode fails, set minimal info
      userInfo.value = { id: 0, username: usernameInput, is_admin: false }
      localStorage.setItem('user_info', JSON.stringify(userInfo.value))
    }
  }

  function logout() {
    accessToken.value = null
    refreshToken.value = null
    userInfo.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem('user_info')
    router.push({ name: 'Login' })
  }

  async function refreshAccessToken() {
    if (!refreshToken.value) {
      logout()
      return
    }

    try {
      const response = await http.post('/auth/refresh', {
        refresh_token: refreshToken.value,
      })

      const { access_token, refresh_token: newRefreshToken } = response.data
      accessToken.value = access_token
      refreshToken.value = newRefreshToken
      localStorage.setItem('access_token', access_token)
      localStorage.setItem('refresh_token', newRefreshToken)
    } catch {
      logout()
    }
  }

  async function register(usernameInput: string, password: string) {
    const data = await apiRegister({ username: usernameInput, password })

    const { access_token, refresh_token } = data

    // Store tokens
    accessToken.value = access_token
    refreshToken.value = refresh_token
    localStorage.setItem('access_token', access_token)
    localStorage.setItem('refresh_token', refresh_token)

    // Decode user info from token payload (JWT)
    try {
      const payload = JSON.parse(atob(access_token.split('.')[1]))
      const info: UserInfo = {
        id: payload.sub || payload.user_id,
        username: payload.username || usernameInput,
        is_admin: payload.is_admin || false,
      }
      userInfo.value = info
      localStorage.setItem('user_info', JSON.stringify(info))
    } catch {
      userInfo.value = { id: 0, username: usernameInput, is_admin: false }
      localStorage.setItem('user_info', JSON.stringify(userInfo.value))
    }
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    isAuthenticated,
    isAdmin,
    username,
    login,
    register,
    logout,
    refreshAccessToken,
  }
})
