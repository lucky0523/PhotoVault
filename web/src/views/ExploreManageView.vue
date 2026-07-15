<template>
  <div class="explore-manage-view">
    <el-page-header title="返回探索" @back="goBack">
      <template #content>
        <span class="header-content">
          分析资源管理
          <el-button
            v-if="authStore.isAdmin"
            type="primary"
            :loading="reanalyzing"
            @click="handleReanalyze"
          >
            重新分析
          </el-button>
        </span>
      </template>
    </el-page-header>

    <el-alert
      v-if="!authStore.isAdmin"
      class="admin-hint"
      type="info"
      :closable="false"
      show-icon
      title="仅管理员可上传/更新资源文件或触发重新分析，以下为只读状态。"
    />

    <!-- Runtime dependency warning: face/scene need onnxruntime. -->
    <el-alert
      v-if="status && !status.runtime_ok && (status.people.enabled || status.scenes.enabled)"
      class="runtime-warn"
      type="warning"
      :closable="false"
      show-icon
      title="缺少 ONNX 运行时"
      description="人物 / 场景分析需要 onnxruntime（在服务端执行 pip install onnxruntime 并重启）。仅安装模型文件不足以运行推理。地点分析不受影响。"
    />

    <!-- Analysis status overview -->
    <el-card class="status-card" shadow="never">
      <template #header>
        <div class="status-header">
          <span>分析状态</span>
          <span class="status-sub">
            共 {{ status?.total_images ?? 0 }} 张照片
            <template v-if="(status?.queue_pending ?? 0) > 0">
              · 队列待分析 {{ status?.queue_pending }}
            </template>
            <el-button link size="small" @click="loadStatus">刷新</el-button>
          </span>
        </div>
      </template>
      <div class="status-grid">
        <div v-for="s in statusItems" :key="s.key" class="status-item">
          <div class="status-title">
            {{ s.title }}
            <el-tag
              size="small"
              :type="s.dim?.enabled ? 'success' : 'info'"
            >{{ s.dim?.enabled ? '已启用' : '未启用' }}</el-tag>
          </div>
          <div class="status-metric">{{ s.dim?.photos ?? 0 }}</div>
          <div class="status-desc">{{ s.groupLabel }}：{{ s.dim?.groups ?? 0 }}</div>
          <div v-if="!s.dim?.installed" class="status-warn">未安装资源</div>
        </div>
      </div>
    </el-card>

    <div class="resource-cards" v-loading="loading">
      <el-card
        v-for="card in cards"
        :key="card.type"
        class="resource-card"
        shadow="hover"
      >
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ card.title }}</span>
            <el-tag
              :type="statusOf(card.type)?.installed ? 'success' : 'info'"
              size="small"
            >
              {{ statusOf(card.type)?.installed ? '已安装' : '未安装' }}
            </el-tag>
          </div>
        </template>

        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="名称">
            {{ statusOf(card.type)?.name || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="版本">
            {{ statusOf(card.type)?.version || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="大小">
            {{ formatSize(statusOf(card.type)?.size) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDate(statusOf(card.type)?.updated_at) }}
          </el-descriptions-item>
          <el-descriptions-item label="功能开关">
            <el-switch
              v-if="authStore.isAdmin"
              :model-value="flags[flagKeyOf(card.type)]"
              :loading="flagSaving"
              @change="(val: any) => handleToggle(card.type, !!val)"
            />
            <el-tag
              v-else
              :type="flags[flagKeyOf(card.type)] ? 'success' : 'info'"
              size="small"
            >
              {{ flags[flagKeyOf(card.type)] ? '已启用' : '未启用' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="authStore.isAdmin" class="card-actions">
          <!-- One-click download / update from a URL (default pre-filled). -->
          <div class="url-download">
            <el-input
              v-model="urls[card.type]"
              size="small"
              placeholder="模型 / 数据库下载地址 (http/https，支持 .onnx/.json/.db/.zip)"
              :disabled="downloading[card.type]"
              clearable
            />
            <el-button
              type="primary"
              size="small"
              :loading="downloading[card.type]"
              :disabled="!urls[card.type]"
              @click="handleDownload(card.type)"
            >
              下载 / 更新
            </el-button>
          </div>

          <!-- Live download progress (polled from the server). -->
          <div v-if="downloading[card.type]" class="download-progress">
            <el-progress
              :percentage="progress[card.type]?.percent ?? 0"
              :indeterminate="progress[card.type]?.percent == null"
              :duration="3"
              :stroke-width="10"
            />
            <div class="progress-text">{{ progressText(card.type) }}</div>
          </div>

          <div class="download-tip">{{ card.hint }}</div>

          <el-divider class="action-divider">或手动上传文件</el-divider>

          <el-upload
            :show-file-list="false"
            :accept="card.accept"
            :auto-upload="false"
            :on-change="(file: UploadFile) => handleFileChange(card.type, file)"
          >
            <el-button
              plain
              size="small"
              :loading="uploadingType === card.type"
            >
              上传本地文件
            </el-button>
            <template #tip>
              <div class="upload-tip">支持 {{ card.accept }}，由服务端校验文件</div>
            </template>
          </el-upload>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  getResources,
  uploadResource,
  downloadResource,
  getDownloadProgress,
  reanalyze,
  getAnalysisSettings,
  updateAnalysisSettings,
  DEFAULT_RESOURCE_URLS,
} from '@/api/explore'
import { getAnalysisStatus } from '@/api/explore'
import type {
  ResourcesResponse,
  ResourceStatus,
  DownloadProgress,
  AnalysisFlags,
  AnalysisStatus,
} from '@/api/explore'
import { formatFileSize, formatDate } from '@/api/files'

type ResourceType = 'face' | 'scene' | 'geocoding'

interface CardMeta {
  type: ResourceType
  title: string
  accept: string
  hint: string
}

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const reanalyzing = ref(false)
const uploadingType = ref<ResourceType | null>(null)
// Per-type download flag so the three resources can download concurrently
// (the backend allows concurrent downloads of different types; only the same
// type is blocked while in flight).
const downloading = reactive<Record<ResourceType, boolean>>({
  face: false,
  scene: false,
  geocoding: false,
})
const resources = ref<ResourcesResponse | null>(null)

const cards: CardMeta[] = [
  {
    type: 'face',
    title: '人脸模型 (FaceModel)',
    accept: '.onnx,.zip',
    hint: 'InsightFace buffalo_l 模型包 (.zip)，自动解压出检测/特征模型。仅限非商业用途。',
  },
  {
    type: 'scene',
    title: '场景模型 (SceneModel)',
    accept: '.onnx,.json,.zip',
    hint: 'Places365 无官方 ONNX，需自行从 PyTorch 导出后填入地址或手动上传 scene.onnx + labels_zh.json。',
  },
  {
    type: 'geocoding',
    title: '地理编码库 (GeocodingDB)',
    accept: '.db,.zip',
    hint: 'GeoNames 城市数据 (.zip)，服务端自动转换为 cities.db。',
  },
]

// URL inputs per type, pre-filled with sensible open-source defaults.
const urls = reactive<Record<ResourceType, string>>({
  face: DEFAULT_RESOURCE_URLS.face || '',
  scene: DEFAULT_RESOURCE_URLS.scene || '',
  geocoding: DEFAULT_RESOURCE_URLS.geocoding || '',
})

// Live download progress per type (populated while a download is running).
const progress = reactive<Record<ResourceType, DownloadProgress | null>>({
  face: null,
  scene: null,
  geocoding: null,
})

// Runtime analysis feature flags (toggleable, no restart).
const flags = reactive<AnalysisFlags>({
  enable_place: false,
  enable_scene: false,
  enable_face: false,
})
const flagSaving = ref(false)

// Analysis status overview.
const status = ref<AnalysisStatus | null>(null)

const statusItems = computed(() => [
  { key: 'people', title: '人物', dim: status.value?.people, groupLabel: '人物分组' },
  { key: 'places', title: '地点', dim: status.value?.places, groupLabel: '城市' },
  { key: 'scenes', title: '场景', dim: status.value?.scenes, groupLabel: '场景标签' },
])

async function loadStatus() {
  try {
    status.value = await getAnalysisStatus()
  } catch (error) {
    console.error('Failed to load analysis status:', error)
  }
}

function flagKeyOf(type: ResourceType): keyof AnalysisFlags {
  if (type === 'face') return 'enable_face'
  if (type === 'scene') return 'enable_scene'
  return 'enable_place'
}

async function loadFlags() {
  try {
    Object.assign(flags, await getAnalysisSettings())
  } catch (error) {
    console.error('Failed to load analysis flags:', error)
  }
}

async function handleToggle(type: ResourceType, val: boolean) {
  const key = flagKeyOf(type)
  const previous = flags[key]
  flags[key] = val
  flagSaving.value = true
  try {
    Object.assign(flags, await updateAnalysisSettings({ ...flags }))
    ElMessage.success(val ? '已启用' : '已关闭')
  } catch (error: any) {
    flags[key] = previous // revert on failure
    const msg = error.response?.data?.detail || '设置失败'
    ElMessage.error(msg)
  } finally {
    flagSaving.value = false
  }
}

function progressText(type: ResourceType): string {
  const p = progress[type]
  if (!p) return '准备中...'
  if (p.phase === 'installing') return '正在安装 / 解压...'
  if (p.total) {
    return `${formatFileSize(p.downloaded)} / ${formatFileSize(p.total)}`
  }
  return `已下载 ${formatFileSize(p.downloaded)}（大小未知）`
}

function statusOf(type: ResourceType): ResourceStatus | undefined {
  return resources.value?.[type]
}

function formatSize(size: number | undefined): string {
  if (size === undefined || size === null) return '-'
  return formatFileSize(size)
}

async function loadResources() {
  loading.value = true
  try {
    resources.value = await getResources()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '加载资源状态失败'
    ElMessage.error(msg)
    console.error('Failed to load resources:', error)
  } finally {
    loading.value = false
  }
}

async function handleFileChange(type: ResourceType, uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (!raw) return

  uploadingType.value = type
  try {
    const result = await uploadResource(type, raw)
    ElMessage.success(result.message || '资源更新成功')
    await loadResources()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '资源上传失败'
    ElMessage.error(msg)
  } finally {
    uploadingType.value = null
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function handleDownload(type: ResourceType) {
  const url = urls[type]?.trim()
  if (!url) return

  downloading[type] = true
  progress[type] = {
    status: 'running',
    phase: 'downloading',
    downloaded: 0,
    total: null,
    percent: null,
    message: '',
  }

  try {
    await downloadResource(type, url)

    // Poll the server for progress until the job finishes.
    // eslint-disable-next-line no-constant-condition
    while (true) {
      await sleep(800)
      const p = await getDownloadProgress(type)
      progress[type] = p
      if (p.status === 'success') {
        ElMessage.success(p.message || '资源下载成功')
        await loadResources()
        break
      }
      if (p.status === 'error') {
        ElMessage.error(p.error || '资源下载失败')
        break
      }
    }
  } catch (error: any) {
    const msg = error.response?.data?.detail || '资源下载失败'
    ElMessage.error(msg)
  } finally {
    downloading[type] = false
    progress[type] = null
  }
}

async function handleReanalyze() {
  reanalyzing.value = true
  try {
    const result = await reanalyze()
    ElMessage.success(`已触发重新分析，共加入 ${result.queued} 项任务`)
    await loadStatus()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '触发重新分析失败'
    ElMessage.error(msg)
  } finally {
    reanalyzing.value = false
  }
}

function goBack() {
  router.push('/explore')
}

onMounted(() => {
  loadResources()
  loadFlags()
  loadStatus()
})
</script>

<style scoped>
.explore-manage-view {
  padding: 20px;
}

.header-content {
  display: inline-flex;
  align-items: center;
  gap: 16px;
}

.admin-hint {
  margin-top: 16px;
}

.runtime-warn {
  margin-top: 16px;
}

.status-card {
  margin-top: 20px;
}

.status-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.status-sub {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 16px;
}

.status-item {
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.status-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #303133;
}

.status-metric {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 600;
  color: #303133;
}

.status-desc {
  font-size: 12px;
  color: #909399;
}

.status-warn {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
}

.resource-cards {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
  min-height: 200px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.card-actions {
  margin-top: 16px;
}

.url-download {
  display: flex;
  gap: 8px;
  align-items: center;
}

.url-download .el-input {
  flex: 1;
}

.download-progress {
  margin-top: 10px;
}

.progress-text {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}

.download-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.action-divider {
  margin: 16px 0 12px;
  font-size: 12px;
  color: #c0c4cc;
}

.upload-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
</style>
