import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api/http'

interface UserProfile {
  id: number
  username: string
  is_admin: boolean
  created_at: string
}

export const useUserStore = defineStore('user', () => {
  // State
  const profile = ref<UserProfile | null>(null)
  const loading = ref(false)

  // Actions
  async function fetchProfile() {
    loading.value = true
    try {
      const response = await http.get('/auth/me')
      profile.value = response.data
    } catch {
      profile.value = null
    } finally {
      loading.value = false
    }
  }

  function clearProfile() {
    profile.value = null
  }

  return {
    profile,
    loading,
    fetchProfile,
    clearProfile,
  }
})
