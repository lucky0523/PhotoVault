<template>
  <div class="explore-view">
    <!-- Top bar: title + library filter + manage entry -->
    <div class="explore-toolbar">
      <div class="toolbar-left">
        <el-icon :size="20"><Compass /></el-icon>
        <span class="toolbar-title">探索</span>
        <span class="library-label">图库：</span>
        <el-select
          v-model="library"
          size="default"
          class="library-filter"
          placeholder="全部图库"
          @change="reloadAll"
        >
          <el-option label="全部图库" value="" />
          <el-option
            v-for="dev in devices"
            :key="dev.name"
            :label="dev.name"
            :value="dev.name"
          />
        </el-select>
        <el-button @click="goManage">
          <el-icon><Setting /></el-icon>
          管理
        </el-button>
      </div>
    </div>

    <div class="explore-content">
      <!-- People section: circular avatars -->
      <section class="explore-section">
        <div class="section-title">人物</div>
        <div v-if="people.loading" class="section-loading">
          <el-icon class="is-loading" :size="24"><Loading /></el-icon>
          <span>加载中...</span>
        </div>
        <el-empty
          v-else-if="people.items.length === 0"
          :image-size="80"
          description="暂无数据，需在管理入口安装模型"
        />
        <div v-else class="scroll-row">
          <div
            v-for="person in people.items"
            :key="person.cluster_id"
            class="person-item"
            @click="openPerson(person)"
          >
            <div class="person-avatar">
              <img
                v-if="person.cover_file_id"
                :src="getThumbnailUrl(person.cover_file_id, 'small')"
                :alt="person.name"
                loading="lazy"
                @error="handleThumbError"
              />
              <el-icon v-else :size="32" class="person-placeholder"><User /></el-icon>
            </div>
            <span class="person-name">{{ person.name }}</span>
          </div>
        </div>
      </section>

      <!-- Places section: map entry + city cards -->
      <section class="explore-section">
        <div class="section-title">地点</div>
        <div v-if="places.loading" class="section-loading">
          <el-icon class="is-loading" :size="24"><Loading /></el-icon>
          <span>加载中...</span>
        </div>
        <template v-else>
          <div class="scroll-row">
            <!-- Map entry card is always shown first. It navigates to the places
                 overview using the special sentinel city "__map__"; CategoryPhotosView
                 (task 4.6) interprets "__map__" as the by-city summary / map overview
                 (per design 5.3). This is the agreed contract between the two views. -->
            <div class="place-card map-card" @click="openMap">
              <div class="place-cover map-cover">
                <el-icon :size="36"><MapLocation /></el-icon>
              </div>
              <span class="place-name">地图</span>
            </div>
            <div
              v-for="place in places.items"
              :key="place.city"
              class="place-card"
              @click="openPlace(place)"
            >
              <div class="place-cover">
                <img
                  v-if="place.cover_file_id"
                  :src="getThumbnailUrl(place.cover_file_id, 'small')"
                  :alt="place.city"
                  loading="lazy"
                  @error="handleThumbError"
                />
                <el-icon v-else :size="28" class="cover-placeholder"><Picture /></el-icon>
              </div>
              <span class="place-name">{{ place.city }}</span>
              <span class="place-count">{{ place.count }} 张</span>
            </div>
          </div>
          <!-- When there are no city groups (only the map entry), hint the degradation -->
          <div v-if="places.items.length === 0" class="section-hint">
            暂无地点数据，需在管理入口安装地理编码库
          </div>
        </template>
      </section>

      <!-- Scenes section: rectangular cards -->
      <section class="explore-section">
        <div class="section-title">场景</div>
        <div v-if="scenes.loading" class="section-loading">
          <el-icon class="is-loading" :size="24"><Loading /></el-icon>
          <span>加载中...</span>
        </div>
        <el-empty
          v-else-if="scenes.items.length === 0"
          :image-size="80"
          description="暂无数据，需在管理入口安装模型"
        />
        <div v-else class="scroll-row">
          <div
            v-for="scene in scenes.items"
            :key="scene.label"
            class="place-card"
            @click="openScene(scene)"
          >
            <div class="place-cover">
              <img
                v-if="scene.cover_file_id"
                :src="getThumbnailUrl(scene.cover_file_id, 'small')"
                :alt="scene.name_zh"
                loading="lazy"
                @error="handleThumbError"
              />
              <el-icon v-else :size="28" class="cover-placeholder"><Picture /></el-icon>
            </div>
            <span class="place-name">{{ scene.name_zh }}</span>
            <span class="place-count">{{ scene.count }} 张</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  Compass,
  Setting,
  Loading,
  User,
  Picture,
  MapLocation,
} from '@element-plus/icons-vue'
import {
  getPeople,
  getPlaces,
  getScenes,
  getThumbnailUrl,
  type PersonCluster,
  type PlaceGroup,
  type SceneGroup,
} from '@/api/explore'
import { getDeviceStats, type DeviceStats } from '@/api/files'

const router = useRouter()

// LibraryFilter: '' means「全部图库」(all libraries). Options come from getDeviceStats().
const library = ref('')
const devices = ref<DeviceStats[]>([])

// Each section tracks its own loading state and items so it can load/degrade
// independently (Req 9.1).
const people = reactive<{ loading: boolean; items: PersonCluster[] }>({
  loading: false,
  items: [],
})
const places = reactive<{ loading: boolean; items: PlaceGroup[] }>({
  loading: false,
  items: [],
})
const scenes = reactive<{ loading: boolean; items: SceneGroup[] }>({
  loading: false,
  items: [],
})

async function loadDevices() {
  try {
    devices.value = await getDeviceStats()
  } catch {
    devices.value = []
  }
}

async function loadPeople() {
  people.loading = true
  try {
    people.items = await getPeople(library.value)
  } catch {
    // Never error/white-screen — fall back to the empty placeholder (Req 9.1).
    people.items = []
  } finally {
    people.loading = false
  }
}

async function loadPlaces() {
  places.loading = true
  try {
    places.items = await getPlaces(library.value)
  } catch {
    places.items = []
  } finally {
    places.loading = false
  }
}

async function loadScenes() {
  scenes.loading = true
  try {
    scenes.items = await getScenes(library.value)
  } catch {
    scenes.items = []
  } finally {
    scenes.loading = false
  }
}

// Reload all three sections with the current library value.
function reloadAll() {
  loadPeople()
  loadPlaces()
  loadScenes()
}

// Navigation --------------------------------------------------------------

function goManage() {
  router.push({ name: 'ExploreManage' })
}

function openPerson(person: PersonCluster) {
  // Pass the display name via query so the detail view can show it without
  // an extra lookup.
  router.push({
    name: 'ExplorePeople',
    params: { id: String(person.cluster_id) },
    query: { name: person.name },
  })
}

function openPlace(place: PlaceGroup) {
  router.push({ name: 'ExplorePlaces', params: { city: place.city } })
}

// The 地图 entry routes to the places overview via the "__map__" sentinel.
// CategoryPhotosView interprets it as the by-city summary (design 5.3).
function openMap() {
  router.push({ name: 'ExplorePlaces', params: { city: '__map__' } })
}

function openScene(scene: SceneGroup) {
  router.push({ name: 'ExploreScenes', params: { label: scene.label } })
}

function handleThumbError(e: Event) {
  const img = e.target as HTMLImageElement
  img.src =
    'data:image/svg+xml,' +
    encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect fill="#f0f0f0" width="200" height="200"/><text x="100" y="100" text-anchor="middle" fill="#999" font-size="14">无缩略图</text></svg>'
    )
}

onMounted(() => {
  loadDevices()
  reloadAll()
})
</script>

<style scoped>
.explore-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Toolbar */
.explore-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  margin: 16px 16px 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.library-label {
  font-size: 14px;
  color: #606266;
  margin-left: 8px;
}

.library-filter {
  width: 160px;
}

/* Content */
.explore-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.explore-section {
  margin-bottom: 28px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.section-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  padding: 24px 0;
}

.section-hint {
  color: #909399;
  font-size: 13px;
  margin-top: 8px;
}

/* Horizontal scroll row */
.scroll-row {
  display: flex;
  gap: 16px;
  overflow-x: auto;
  padding-bottom: 8px;
}

/* People: circular avatars */
.person-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex-shrink: 0;
  width: 88px;
}

.person-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  background: #f0f0f0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e4e7ed;
  transition: box-shadow 0.2s;
}

.person-item:hover .person-avatar {
  box-shadow: 0 0 0 2px var(--el-color-primary);
}

.person-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.person-placeholder {
  color: #c0c4cc;
}

.person-name {
  font-size: 13px;
  color: #303133;
  max-width: 88px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
}

/* Places & Scenes: rectangular cards */
.place-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
  flex-shrink: 0;
  width: 140px;
}

.place-cover {
  width: 140px;
  height: 100px;
  border-radius: 8px;
  overflow: hidden;
  background: #f0f0f0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e4e7ed;
  transition: box-shadow 0.2s;
}

.place-card:hover .place-cover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  border-color: var(--el-color-primary);
}

.place-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  color: #c0c4cc;
}

.map-card .map-cover {
  background: linear-gradient(135deg, var(--el-color-primary-light-3), var(--el-color-primary));
  color: #fff;
}

.place-name {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.place-count {
  font-size: 12px;
  color: #909399;
}
</style>
