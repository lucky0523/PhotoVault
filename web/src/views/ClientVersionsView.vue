<template>
  <div class="client-versions-view pv-page">
    <PageHeader
      title="客户端管理"
      subtitle="上传和管理 PhotoVault Android 客户端"
      :icon="Cellphone"
    >
      <template #extra>
        <el-button type="primary" @click="showUploadDialog = true">
          上传 Android APK
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      class="storage-hint"
      type="info"
      :closable="false"
      show-icon
      title="客户端文件保存在工作目录的独立区域，不会写入照片目录或照片记录。"
    />

    <div class="versions-table pv-panel" v-loading="loading">
      <el-empty v-if="!loading && versions.length === 0" description="尚未上传 Android 客户端" />
      <el-table v-else :data="versions" stripe>
        <el-table-column label="版本" min-width="150">
          <template #default="{ row }">
            <div class="version-cell">
              <strong>{{ row.version_name }}</strong>
              <el-tag v-if="row.is_latest" type="success" size="small">最新版</el-tag>
            </div>
            <span class="secondary-text">versionCode {{ row.version_code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="package_name" label="包名" min-width="190" />
        <el-table-column label="文件" min-width="180">
          <template #default="{ row }">
            <div>{{ row.original_filename }}</div>
            <span class="secondary-text">{{ formatSize(row.file_size) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="release_notes" label="更新说明" min-width="180">
          <template #default="{ row }">{{ row.release_notes || '-' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="170">
          <template #default="{ row }">{{ formatDate(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.is_latest && row.download_url"
              link
              type="primary"
              @click="openDownload(row.download_url)"
            >
              下载
            </el-button>
            <el-popconfirm
              title="确定删除这个客户端版本吗？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleDelete(row.id)"
            >
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="showUploadDialog"
      title="上传 Android 客户端"
      width="520px"
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      @closed="resetUploadForm"
    >
      <el-form label-position="top">
        <el-form-item label="APK 文件" required>
          <el-upload
            ref="uploadRef"
            class="apk-upload"
            drag
            accept=".apk,application/vnd.android.package-archive"
            :auto-upload="false"
            :limit="1"
            :disabled="uploading"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖放 APK 到这里，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">
                服务端将解析并校验包名必须为 com.huoyi.photovault，最大 500 MB。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="更新说明">
          <el-input
            v-model="releaseNotes"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="可选，填写此版本的主要更新内容"
            :disabled="uploading"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="uploading" @click="showUploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!selectedFile" @click="handleUpload">
          上传并校验
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadInstance } from 'element-plus'
import { Cellphone } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  deleteClientVersion,
  listClientVersions,
  uploadClientVersion,
} from '@/api/clientVersions'
import type { ClientVersionInfo } from '@/api/clientVersions'

const loading = ref(false)
const uploading = ref(false)
const versions = ref<ClientVersionInfo[]>([])
const showUploadDialog = ref(false)
const uploadRef = ref<UploadInstance>()
const selectedFile = ref<File | null>(null)
const releaseNotes = ref('')

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(value: string): string {
  if (!value) return '-'
  return new Date(value.replace(' ', 'T') + (value.includes('Z') ? '' : 'Z')).toLocaleString('zh-CN')
}

async function loadVersions() {
  loading.value = true
  try {
    versions.value = await listClientVersions()
  } catch (error) {
    console.error('Failed to load client versions:', error)
    ElMessage.error('加载客户端版本失败')
  } finally {
    loading.value = false
  }
}

function handleFileChange(uploadFile: UploadFile) {
  selectedFile.value = uploadFile.raw || null
}

function handleFileRemove() {
  selectedFile.value = null
}

function resetUploadForm() {
  uploadRef.value?.clearFiles()
  selectedFile.value = null
  releaseNotes.value = ''
}

function getErrorMessage(error: any, fallback: string): string {
  const detail = error.response?.data?.detail
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    return detail
      .map((item) => {
        const field = Array.isArray(item?.loc) ? item.loc.at(-1) : ''
        return `${field ? `${field}: ` : ''}${item?.msg || '请求参数无效'}`
      })
      .join('；')
  }
  return fallback
}

async function handleUpload() {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    const version = await uploadClientVersion(selectedFile.value, releaseNotes.value)
    ElMessage.success(`Android ${version.version_name} 上传成功`)
    showUploadDialog.value = false
    await loadVersions()
  } catch (error: any) {
    ElMessage.error(getErrorMessage(error, '上传 APK 失败'))
  } finally {
    uploading.value = false
  }
}

async function handleDelete(versionId: number) {
  try {
    await deleteClientVersion(versionId)
    ElMessage.success('客户端版本已删除')
    await loadVersions()
  } catch (error: any) {
    ElMessage.error(error.response?.data?.detail || '删除客户端版本失败')
  }
}

function openDownload(path: string) {
  window.open(path, '_blank', 'noopener,noreferrer')
}

onMounted(loadVersions)
</script>

<style scoped>
.storage-hint {
  margin-bottom: var(--pv-gap);
}

.versions-table {
  overflow: hidden;
}

.version-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.secondary-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.apk-upload {
  width: 100%;
}

.apk-upload :deep(.el-upload),
.apk-upload :deep(.el-upload-dragger) {
  width: 100%;
}
</style>
