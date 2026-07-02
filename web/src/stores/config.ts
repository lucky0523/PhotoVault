import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getFilesConfig } from '@/api/files'

export const useConfigStore = defineStore('config', () => {
  // Trash retention window (days) before auto-purge. Server-configurable;
  // 30 is only a fallback until the real value is fetched.
  const trashRetentionDays = ref(30)
  const loaded = ref(false)

  async function ensureLoaded() {
    if (loaded.value) return
    try {
      const cfg = await getFilesConfig()
      trashRetentionDays.value = cfg.trash_retention_days
      loaded.value = true
    } catch {
      // keep the fallback value on failure
    }
  }

  return {
    trashRetentionDays,
    loaded,
    ensureLoaded,
  }
})
