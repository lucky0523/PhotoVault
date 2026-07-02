import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listTrash } from '@/api/files'

export const useTrashStore = defineStore('trash', () => {
  // State: number of files currently in the recycle bin
  const count = ref(0)
  const loading = ref(false)

  // Actions
  async function refresh() {
    loading.value = true
    try {
      // page_size=1 is enough — we only need the total
      const res = await listTrash(1, 1)
      count.value = res.total
    } catch {
      // keep the previous value on failure
    } finally {
      loading.value = false
    }
  }

  return {
    count,
    loading,
    refresh,
  }
})
