<template>
  <div class="trash-view">
    <div class="toolbar">
      <div class="toolbar-left">
        <h3>回收站</h3>
        <span class="hint">文件将在 {{ configStore.trashRetentionDays }} 天后自动彻底删除</span>
      </div>
      <div class="toolbar-right">
        <el-button
          type="primary"
          :disabled="selectedItems.length === 0"
          :loading="restoreSelectedLoading"
          @click="handleRestoreSelected"
        >
          恢复已选的 {{ selectedItems.length }} 个文件
        </el-button>
        <el-button
          type="success"
          :disabled="items.length === 0"
          :loading="restoreAllLoading"
          @click="handleRestoreAll"
        >
          恢复所有文件
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="selectedItems.length === 0"
          :loading="purgeSelectedLoading"
          @click="handlePurgeSelected"
        >
          彻底删除已选的 {{ selectedItems.length }} 个文件
        </el-button>
        <el-button
          type="danger"
          :disabled="items.length === 0"
          :loading="purgeAllLoading"
          @click="handlePurgeAll"
        >
          清空回收站
        </el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="items"
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="40" />
      <el-table-column label="预览" width="80">
        <template #default="{ row }">
          <img
            :src="getThumbnailUrl(row.id, 'small')"
            class="thumbnail"
            @error="handleImageError"
          />
        </template>
      </el-table-column>
      <el-table-column prop="file_name" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">
          {{ formatFileSize(row.file_size) }}
        </template>
      </el-table-column>
      <el-table-column prop="display_path" label="原始路径" min-width="200" show-overflow-tooltip />
      <el-table-column prop="device_name" label="设备" width="120" />
      <el-table-column label="删除时间" width="170">
        <template #default="{ row }">
          {{ formatDate(row.deleted_at) }}
        </template>
      </el-table-column>
      <el-table-column label="剩余时间" width="120">
        <template #default="{ row }">
          {{ formatRemainingTime(row.expires_at) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="handleRestore(row)">恢复</el-button>
          <el-button type="danger" link @click="handlePurge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="items.length === 0 && !loading" class="empty-state">
      <el-empty description="回收站为空" />
    </div>

    <el-pagination
      v-if="total > pageSize"
      class="pagination"
      :current-page="currentPage"
      :page-size="pageSize"
      :total="total"
      layout="prev, pager, next, total"
      @current-change="handlePageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTrash,
  restoreFile,
  purgeFile,
  purgeAll,
  getThumbnailUrl,
  formatFileSize,
  formatDate,
  formatRemainingTime,
} from '@/api/files'
import type { TrashItem } from '@/api/files'
import { useTrashStore } from '@/stores/trash'
import { useConfigStore } from '@/stores/config'

const trashStore = useTrashStore()
const configStore = useConfigStore()

const items = ref<TrashItem[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(50)
const loading = ref(false)
const purgeAllLoading = ref(false)
const purgeSelectedLoading = ref(false)
const restoreSelectedLoading = ref(false)
const restoreAllLoading = ref(false)
const selectedItems = ref<TrashItem[]>([])

async function loadTrash() {
  loading.value = true
  try {
    const res = await listTrash(currentPage.value, pageSize.value)
    items.value = res.items
    total.value = res.total
    // keep the sidebar badge in sync
    trashStore.count = res.total
  } catch (e) {
    ElMessage.error('加载回收站失败')
  } finally {
    loading.value = false
  }
}

function handleSelectionChange(selection: TrashItem[]) {
  selectedItems.value = selection
}

function handleImageError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

async function handleRestore(row: TrashItem) {
  try {
    await ElMessageBox.confirm(
      `确认恢复文件 "${row.file_name}" 吗？`,
      '恢复文件',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
    await restoreFile(row.id)
    ElMessage.success('文件已恢复')
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('恢复失败')
    }
  }
}

async function handleRestoreSelected() {
  if (selectedItems.value.length === 0) return
  
  try {
    await ElMessageBox.confirm(
      `确认恢复选中的 ${selectedItems.value.length} 个文件吗？`,
      '恢复文件',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
    restoreSelectedLoading.value = true
    let successCount = 0
    for (const item of selectedItems.value) {
      try {
        await restoreFile(item.id)
        successCount++
      } catch {
      }
    }
    ElMessage.success(`已成功恢复 ${successCount} 个文件`)
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('恢复失败')
    }
  } finally {
    restoreSelectedLoading.value = false
  }
}

async function handleRestoreAll() {
  if (items.value.length === 0) return
  
  try {
    await ElMessageBox.confirm(
      `确认恢复回收站中的所有 ${items.value.length} 个文件吗？`,
      '恢复所有文件',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
    restoreAllLoading.value = true
    let successCount = 0
    for (const item of items.value) {
      try {
        await restoreFile(item.id)
        successCount++
      } catch {
      }
    }
    ElMessage.success(`已成功恢复 ${successCount} 个文件`)
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('恢复失败')
    }
  } finally {
    restoreAllLoading.value = false
  }
}

async function handlePurge(row: TrashItem) {
  try {
    await ElMessageBox.confirm(
      `确认彻底删除 "${row.file_name}" 吗？此操作不可恢复！`,
      '彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
    await purgeFile(row.id)
    ElMessage.success('文件已彻底删除')
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handlePurgeSelected() {
  if (selectedItems.value.length === 0) return

  const count = selectedItems.value.length
  try {
    await ElMessageBox.confirm(
      `确认彻底删除选中的 ${count} 个文件吗？此操作不可恢复！`,
      '彻底删除',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
    purgeSelectedLoading.value = true
    let successCount = 0
    for (const item of selectedItems.value) {
      try {
        await purgeFile(item.id)
        successCount++
      } catch {
      }
    }
    const failed = count - successCount
    if (failed > 0) {
      ElMessage.warning(`已彻底删除 ${successCount} 个，${failed} 个失败`)
    } else {
      ElMessage.success(`已彻底删除 ${successCount} 个文件`)
    }
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  } finally {
    purgeSelectedLoading.value = false
  }
}

async function handlePurgeAll() {
  try {
    await ElMessageBox.confirm(
      '确认清空回收站吗？所有文件将被彻底删除，此操作不可恢复！',
      '清空回收站',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
    purgeAllLoading.value = true
    const res = await purgeAll()
    ElMessage.success(`已彻底删除 ${res.count} 个文件`)
    await loadTrash()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清空失败')
    }
  } finally {
    purgeAllLoading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadTrash()
}

onMounted(() => {
  configStore.ensureLoaded()
  loadTrash()
})
</script>

<style scoped>
.trash-view {
  padding: 20px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-left h3 {
  margin: 0;
}

.hint {
  color: #909399;
  font-size: 13px;
}

.thumbnail {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 4px;
}

.empty-state {
  padding: 40px 0;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
</style>
