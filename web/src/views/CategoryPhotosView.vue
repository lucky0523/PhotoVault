<template>
  <div class="pv-page--fill">
    <!-- 统一的页面标题栏。这里的返回是真实可用的（回探索页），
         与设备管理/用户管理那种没有上级页面的场景区分开。 -->
    <div class="pv-page-head">
      <PageHeader
        :title="title"
        :subtitle="countText"
        :icon="headerIcon"
        back
        back-text="返回探索"
        @back="goBack"
      >
        <template #extra>
          <!-- People dimension: rename entry -->
          <el-button
            v-if="dimension === 'people'"
            @click="handleRename"
          >
            <el-icon><EditPen /></el-icon>
            重命名
          </el-button>
        </template>
      </PageHeader>
    </div>

    <div class="pv-page-body">
      <!-- Loading (first page) -->
      <div v-if="loading && !hasContent" class="pv-loading">
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
        <div v-else class="pv-card-grid city-grid">
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
              <span class="city-count pv-muted">{{ place.count }} 张</span>
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
          <!-- 照片瓦片与「图片浏览 / 时间线」共用 pv-tile 规范 -->
          <div class="pv-photo-grid">
            <div
              v-for="(file, index) in files"
              :key="file.id"
              class="pv-tile"
              @click="openPreview(index)"
            >
              <div class="pv-tile__media">
                <img
                  :src="getThumbnailUrl(file.id, 'small')"
                  :alt="file.file_name"
                  loading="lazy"
                  @error="handleThumbnailError"
                />
                <div v-if="isVideo(file)" class="pv-tile__video">
                  <el-icon :size="28"><VideoPlay /></el-icon>
                </div>
                <div v-else-if="isMotionPhoto(file)" class="pv-tile__live">
                  <LivePhotoIcon class="pv-tile__live-icon" />
                  <span>LIVE</span>
                </div>
                <div v-if="file.is_ultra_hdr" class="pv-tile__hdr" title="Ultra HDR">HDR</div>
              </div>
              <div class="pv-tile__overlay">
                <span class="pv-tile__name">{{ file.file_name }}</span>
              </div>
            </div>
          </div>

          <!-- Incremental loading: append pages until all photos are loaded -->
          <div v-if="hasMore" class="load-more-container">
            <el-button :loading="loading" @click="loadMore">加载更多</el-button>
          </div>
        </template>
      </template>
    </div>

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
  EditPen,
  Loading,
  Location,
  Picture,
  User,
  VideoPlay,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImagePreview from '@/components/ImagePreview.vue'
import LivePhotoIcon from '@/components/LivePhotoIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import { isVideo, isMotionPhoto, handleThumbnailError } from '@/utils/media'
import {
  getThumbnailUrl,
  getPeoplePhotos,
  renamePerson,
  getPlacePhotos,
  getPlaces,
  getScenePhotos,
} from '@/api/explore'
import type { ExplorePhoto, PlaceGroup } from '@/api/explore'

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

// 标题栏的副标题与图标：图标跟随维度，与探索页各分区的语义保持一致。
const countText = computed(() =>
  !isMapOverview.value && total.value > 0 ? `${total.value} 张` : ''
)

const headerIcon = computed(() => {
  if (dimension.value === 'people') return User
  if (dimension.value === 'places') return Location
  return Picture
})

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

// Re-load whenever the route (dimension/key) changes.
watch(
  () => route.fullPath,
  () => reload()
)

onMounted(() => reload())
</script>


<style scoped>
/* 页面骨架（pv-page--fill / pv-page-head / pv-page-body）、照片瓦片（pv-tile）、
   加载态（pv-loading）都来自 styles/layout.css，这里只保留本页独有的城市卡片。 */

/* City overview grid：卡片比设备卡片更窄，按需覆盖列宽 token */
.city-grid {
  --pv-card-min: 200px;
}

.city-card {
  border-radius: var(--pv-radius);
  overflow: hidden;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--pv-divider-color);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.city-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: var(--pv-hover-shadow);
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
  color: var(--el-text-color-disabled);
}

.city-info {
  display: flex;
  flex-direction: column;
  padding: 8px 12px;
}

.city-name {
  font-size: 14px;
  color: var(--el-text-color-primary);
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
</style>
