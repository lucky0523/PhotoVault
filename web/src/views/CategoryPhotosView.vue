<template>
  <div class="category-photos-view">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button link @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回探索
        </el-button>
        <span class="view-title">{{ title }}</span>
        <span v-if="!isMapOverview && total > 0" class="view-count">{{ total }} 张</span>
      </div>
      <div class="toolbar-right">
        <!-- People dimension: rename entry -->
        <el-button
          v-if="dimension === 'people'"
          size="small"
          @click="handleRename"
        >
          <el-icon><EditPen /></el-icon>
          重命名
        </el-button>
      </div>
    </div>

    <!-- Loading (first page) -->
    <div v-if="loading && !hasContent" class="loading-container">
      <el-icon class="is-loading" :size="32"><Loading /></el-icon>
      <span>加载中...</span>
    </div>

    <!-- ================= Map overview (地点「地图」入口) ================= -->
    <!--
      Contract with ExploreView (task 4.5): the「地图」aggregation entry in the
      places section routes to /explore/places/__map__. When the city param is
      the sentinel '__map__', this view renders a「按城市汇总的地点总览」instead
      of a photo grid — a grid of city cards, each linking to its own city page.
      This satisfies Requirement 5.3.
    -->
    <template v-if="isMapOverview">
      <el-empty
        v-if="!loading && places.length === 0"
        description="暂无带地理位置的照片"
      />
      <div v-else class="city-grid">
        <div
          v-for="place in places"
          :key="place.city"
          class="city-card"
          @click="openCity(place.city)"
        >
          <div class="city-thumbnail">
            <img
              v-if="place.cover_file_id"
              :src="getThumbnailUrl(place.cover_file_id, 'small')"
              :alt="place.city"
              loading="lazy"
              @error="handleThumbnailError"
            />
            <div v-else class="city-thumbnail-empty">
              <el-icon :size="32"><Location /></el-icon>
            </div>
          </div>
          <div class="city-info">
            <span class="city-name">{{ place.city }}</span>
            <span class="city-count">{{ place.count }} 张</span>
          </div>
        </div>
      </div>
    </template>

    <!-- ================= Photo grid (people / places / scenes) ================= -->
    <template v-else>
      <el-empty
        v-if="!loading && files.length === 0"
        description="该分类下暂无照片"
      />
      <template v-else>
        <div class="files-grid">
          <div
            v-for="(file, index) in files"
            :key="file.id"
            class="file-card"
            @click="openPreview(index)"
          >
            <div class="file-thumbnail">
              <img
                :src="getThumbnailUrl(file.id, 'small')"
                :alt="file.file_name"
                loading="lazy"
                @error="handleThumbnailError"
              />
              <div v-if="isVideo(file)" class="video-badge">
                <el-icon :size="28"><VideoPlay /></el-icon>
              </div>
              <div v-if="file.is_ultra_hdr" class="hdr-badge" title="Ultra HDR">HDR</div>
            </div>
            <div class="file-overlay">
              <span class="file-name">{{ file.file_name }}</span>
            </div>
          </div>
        </div>

        <!-- Incremental loading: append pages until all photos are loaded -->
        <div v-if="hasMore" class="load-more-container">
          <el-button :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </template>
    </template>

    <!-- Image Preview Lightbox -->
    <ImagePreview
      v-model:visible="previewVisible"
      :files="files"
      :initial-index="previewIndex"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  EditPen,
  Loading,
  Location,
  VideoPlay,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImagePreview from '@/components/ImagePreview.vue'
import {
  getThumbnailUrl,
  getPeoplePhotos,
  renamePerson,
  getPlacePhotos,
  getPlaces,
  getScenePhotos,
} from '@/api/explore'
import type { ExplorePhoto, PlaceGroup } from '@/api/explore'
import type { FileInfo } from '@/api/files'

const route = useRoute()
const router = useRouter()

// Sentinel city param used by ExploreView's「地图」aggregation entry.
const MAP_SENTINEL = '__map__'

type Dimension = 'people' | 'places' | 'scenes'

// Resolve the active dimension from the route name (fallback to param presence).
const dimension = computed<Dimension>(() => {
  switch (route.name) {
    case 'ExplorePeople':
      return 'people'
    case 'ExplorePlaces':
      return 'places'
    case 'ExploreScenes':
      return 'scenes'
    default:
      if (route.params.id != null) return 'people'
      if (route.params.city != null) return 'places'
      return 'scenes'
  }
})

// The「地图」overview is a special places sub-mode (city param === '__map__').
const isMapOverview = computed(
  () => dimension.value === 'places' && route.params.city === MAP_SENTINEL
)

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
const files = ref<ExplorePhoto[]>([])
const total = ref(0)
const page = ref(0) // last loaded page (0 = nothing loaded yet)
const pageSize = ref(50)
const loading = ref(false)

// Map overview state
const places = ref<PlaceGroup[]>([])

// Preview state
const previewVisible = ref(false)
const previewIndex = ref(0)

const title = ref('')

const hasContent = computed(() => files.value.length > 0 || places.value.length > 0)
const hasMore = computed(() => !isMapOverview.value && files.value.length < total.value)

// ---------------------------------------------------------------------------
// Title resolution
// ---------------------------------------------------------------------------
function resolveTitle() {
  if (dimension.value === 'people') {
    title.value = (route.query.name as string) || '人物'
  } else if (dimension.value === 'places') {
    title.value = isMapOverview.value ? '地图 · 地点总览' : String(route.params.city)
  } else {
    title.value = (route.query.name_zh as string) || String(route.params.label)
  }
}

// ---------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------
async function fetchPage(nextPage: number) {
  const library = (route.query.library as string) || undefined
  if (dimension.value === 'people') {
    const clusterId = Number(route.params.id)
    return getPeoplePhotos(clusterId, nextPage, pageSize.value, library)
  }
  if (dimension.value === 'places') {
    return getPlacePhotos(String(route.params.city), nextPage, pageSize.value, library)
  }
  return getScenePhotos(String(route.params.label), nextPage, pageSize.value, library)
}

async function loadMore() {
  if (loading.value) return
  loading.value = true
  try {
    const nextPage = page.value + 1
    const response = await fetchPage(nextPage)
    // Append accumulated photos so ImagePreview receives the full list.
    files.value = [...files.value, ...response.files]
    total.value = response.total
    page.value = response.page
  } catch (error) {
    console.error('Failed to load category photos:', error)
    ElMessage.error('加载照片失败')
  } finally {
    loading.value = false
  }
}

async function loadMapOverview() {
  loading.value = true
  try {
    const library = (route.query.library as string) || undefined
    places.value = await getPlaces(library)
  } catch (error) {
    console.error('Failed to load places overview:', error)
    ElMessage.error('加载地点总览失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  files.value = []
  places.value = []
  total.value = 0
  page.value = 0
  previewVisible.value = false
  previewIndex.value = 0
}

async function reload() {
  reset()
  resolveTitle()
  if (isMapOverview.value) {
    await loadMapOverview()
  } else {
    await loadMore()
  }
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------
function goBack() {
  router.push('/explore')
}

function openCity(city: string) {
  router.push({ name: 'ExplorePlaces', params: { city } })
}

function openPreview(index: number) {
  previewIndex.value = index
  previewVisible.value = true
}

// ---------------------------------------------------------------------------
// People rename
// ---------------------------------------------------------------------------
async function handleRename() {
  try {
    const { value } = await ElMessageBox.prompt('请输入人物名称', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: title.value === '人物' ? '' : title.value,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const clusterId = Number(route.params.id)
    const result = await renamePerson(clusterId, value.trim())
    if (result.success) {
      title.value = result.name
      // Keep the query in sync so a refresh preserves the new name.
      router.replace({
        query: { ...route.query, name: result.name },
      })
      ElMessage.success('重命名成功')
    }
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('重命名失败')
    }
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
const VIDEO_EXTENSIONS = [
  'mp4', 'mov', 'mkv', 'webm', '3gp', 'avi', 'mpeg', 'mpg',
  'wmv', 'flv', 'm4v', 'ts', 'm2ts', 'mts',
]

function isVideo(file: FileInfo): boolean {
  if ((file.media_type || '').toLowerCase() === 'video') return true
  if (file.mime_type && file.mime_type.toLowerCase().startsWith('video/')) return true
  const ext = file.file_name.split('.').pop()?.toLowerCase() || ''
  return VIDEO_EXTENSIONS.includes(ext)
}

function handleThumbnailError(e: Event) {
  const img = e.target as HTMLImageElement
  img.src = 'data:image/svg+xml,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect fill="#f0f0f0" width="200" height="200"/><text x="100" y="100" text-anchor="middle" fill="#999" font-size="14">无缩略图</text></svg>'
  )
}

// Re-load whenever the route (dimension/key) changes.
watch(
  () => route.fullPath,
  () => reload()
)

onMounted(() => reload())
</script>

<style scoped>
.category-photos-view {
  padding: 16px;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
}

/* Toolbar */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.view-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.view-count {
  font-size: 13px;
  color: #909399;
}

/* Loading */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
  gap: 12px;
  color: #909399;
}

/* Files grid */
.files-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.file-card {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e4e7ed;
  transition: all 0.2s;
  aspect-ratio: 1;
}

.file-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.file-card:hover .file-overlay {
  opacity: 1;
}

.file-thumbnail {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.file-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-badge {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
  opacity: 0.85;
  pointer-events: none;
  filter: drop-shadow(0 1px 3px rgba(0, 0, 0, 0.5));
}

.hdr-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  border-radius: 4px;
  letter-spacing: 0.5px;
}

.file-overlay {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 6px 8px;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.65), transparent);
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s;
}

.file-name {
  display: block;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Load more */
.load-more-container {
  display: flex;
  justify-content: center;
  padding: 24px 0;
}

/* City overview grid */
.city-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.city-card {
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e4e7ed;
  transition: all 0.2s;
}

.city-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.city-thumbnail {
  width: 100%;
  aspect-ratio: 4 / 3;
  background: #f5f7fa;
}

.city-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.city-thumbnail-empty {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.city-info {
  display: flex;
  flex-direction: column;
  padding: 8px 12px;
}

.city-name {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.city-count {
  font-size: 12px;
  color: #909399;
}
</style>
