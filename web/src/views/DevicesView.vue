<template>
  <div class="devices-view pv-page">
    <PageHeader
      title="设备管理"
      subtitle="管理已备份的设备"
      :icon="Monitor"
    />

    <div v-loading="loading" class="devices-grid">
      <el-empty v-if="!loading && devices.length === 0" description="暂无备份设备" />

      <div v-else class="pv-card-grid">
        <el-card
          v-for="device in devices"
          :key="device.path"
          class="device-card"
          shadow="hover"
          @click="navigateToDevice(device)"
        >
          <div class="device-header">
            <el-icon :size="32" class="device-icon"><Monitor /></el-icon>
            <span class="device-name">{{ device.name }}</span>
          </div>
          <div class="device-info">
            <div class="status-row">
              <div class="status-item">
                <span class="status-dot status-backed-up"></span>
                <span class="status-label">已备份</span>
                <span class="status-value">{{ device.backed_up_count }}</span>
              </div>
              <div class="status-item">
                <span class="status-dot status-trashed"></span>
                <span class="status-label">回收站</span>
                <span class="status-value">{{ device.trashed_count }}</span>
              </div>
              <div class="status-item">
                <span class="status-dot status-purged"></span>
                <span class="status-label">已删除</span>
                <span class="status-value">{{ device.purged_count }}</span>
              </div>
            </div>
            <div class="info-item">
              <span class="info-label">最后备份</span>
              <span class="info-value">{{ formatDate(device.latest_file_time) }}</span>
            </div>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Monitor } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { getDeviceStats, formatDate } from '@/api/files'
import type { DeviceStats } from '@/api/files'

const router = useRouter()
const loading = ref(false)
const devices = ref<DeviceStats[]>([])

async function loadDevices() {
  loading.value = true
  try {
    devices.value = await getDeviceStats()
  } catch (error) {
    console.error('Failed to load devices:', error)
  } finally {
    loading.value = false
  }
}

function navigateToDevice(device: DeviceStats) {
  router.push({ name: 'Photos', query: { path: device.path } })
}

onMounted(() => {
  loadDevices()
})
</script>

<style scoped>
.devices-grid {
  margin-top: var(--pv-page-gutter);
  min-height: 200px;
}

.device-card {
  cursor: pointer;
  border-radius: var(--pv-radius);
  transition: transform 0.2s;
}

.device-card:hover {
  transform: translateY(-2px);
}

.device-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.device-icon {
  color: var(--el-color-primary);
}

.device-name {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.status-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 0;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--pv-divider-color);
}

.status-item {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  justify-content: center;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-backed-up {
  background: var(--el-color-success);
}

.status-trashed {
  background: var(--el-color-warning);
}

.status-purged {
  background: var(--el-color-danger);
}

.status-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  /* 卡片较窄时「已备份」不要被折成两行 */
  white-space: nowrap;
}

.status-value {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label {
  font-size: 13px;
  color: #909399;
}

.info-value {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
}
</style>
