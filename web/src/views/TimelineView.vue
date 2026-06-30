<template>
  <div class="timeline-view">
    <el-container class="timeline-container">
      <!-- Left sidebar: Time axis navigation -->
      <el-aside width="200px" class="time-sidebar">
        <div class="sidebar-header">
          <el-icon><Timer /></el-icon>
          <span>时间轴</span>
        </div>
        <div class="time-nav">
          <div
            v-for="yearGroup in timeNavData"
            :key="yearGroup.year"
            class="year-group"
          >
            <div
              class="year-item"
              :class="{ active: activeYear === yearGroup.year }"
              @click="toggleYear(yearGroup.year)"
            >
              <el-icon class="expand-icon" :class="{ expanded: expandedYears.has(yearGroup.year) }">
                <ArrowRight />
              </el-icon>
              <span class="year-label">{{ yearGroup.year }}年</span>
              <span class="year-count">{{ yearGroup.totalCount }}</span>
            </div>
            <Transition name="expand">
              <div v-if="expandedYears.has(yearGroup.year)" class="month-list">
                <div
                  v-for="month in yearGroup.months"
                  :key="`${yearGroup.year}-${month.month}`"
                  class="month-item"
                  :class="{ active: activeYear === yearGroup.year && activeMonth === month.month }"
                  @click="scrollToSection(yearGroup.year, month.month)"
                >
                  <span class="month-label">{{ month.month }}月</span>
                  <span class="month-count">{{ month.count }}</span>
                </div>
              </div>
            </Transition>
          </div>
          <el-empty v-if="timeNavData.length === 0 && !loading" description="暂无数据" :image-size="60" />
        </div>
      </el-aside>

      <!-- Right content area -->
      <el-main class="content-area">
        <!-- Top toolbar: Date range filter -->
        <div class="toolbar">
          <div class="toolbar-left">
            <el-icon><Calendar /></el-icon>
            <span class="toolbar-title">时间线</span>
            <span v-if="totalPhotos > 0" class="photo-total">共 {{ totalPhotos }} 张照片</span>
          </div>
          <div class="toolbar-right">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              :clearable="true"
              size="default"
              @change="handleDateRangeChange"
            />
          </div>
        </div>

        <!-- Loading state -->
        <div v-if="loading" class="loading-container">
          <el-icon class="is-loading" :size="32"><Loading /></el-icon>
          <span>加载中...</span>
        </div>

        <!-- Empty state -->
        <el-empty
          v-else-if="groupedPhotos.length === 0"
          description="暂无备份图片"
        />

        <!-- Grouped photos by year/month -->
        <div v-else class="photos-content" ref="photosContentRef">
          <div
            v-for="group in groupedPhotos"
            :key="`${group.year}-${group.month}`"
            :ref="(el) => setSectionRef(group.year, group.month, el as HTMLElement)"
            class="photo-group"
          >
            <div class="group-header">
              <h3 class="group-title">{{ group.year }}年{{ group.month }}月</h3>
              <span class="group-count">{{ group.files.length }} 张</span>
            </div>
            <div class="photos-grid">
              <div
                v-for="(file, fileIndex) in group.files"
                :key="file.id"
                class="photo-card"
                @click="openPreview(group, fileIndex)"
              >
                <div class="photo-thumbnail">
                  <img
                    :src="getThumbnailUrl(file.id, 'small')"
                    :alt="file.file_name"
                    loading="lazy"
                    @error="handleThumbnailError"
                  />
                </div>
                <div class="photo-overlay">
                  <span class="photo-name">{{ file.file_name }}</span>
                  <span class="photo-date">{{ formatShortDate(file.exif_time || file.created_at) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>

    <!-- Image Preview Lightbox -->
    <ImagePreview
      v-model:visible="previewVisible"
      :files="previewFiles"
      :initial-index="previewIndex"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { Timer, ArrowRight, Calendar, Loading } from '@element-plus/icons-vue'
import { listFiles, getThumbnailUrl } from '@/api/files'
import type { FileInfo } from '@/api/files'
import ImagePreview from '@/components/ImagePreview.vue'

// --- Types ---
interface MonthGroup {
  year: number
  month: number
  files: FileInfo[]
}

interface YearNavItem {
  year: number
  totalCount: number
  months: { month: number; count: number }[]
}

// --- State ---
const loading = ref(false)
const allFiles = ref<FileInfo[]>([])
const dateRange = ref<[string, string] | null>(null)
const expandedYears = ref<Set<number>>(new Set())
const activeYear = ref<number | null>(null)
const activeMonth = ref<number | null>(null)

// Preview state
const previewVisible = ref(false)
const previewFiles = ref<FileInfo[]>([])
const previewIndex = ref(0)

// Section refs for scrolling
const sectionRefs = ref<Map<string, HTMLElement>>(new Map())
const photosContentRef = ref<HTMLElement | null>(null)

// --- Computed ---
const filteredFiles = computed(() => {
  if (!dateRange.value || !dateRange.value[0] || !dateRange.value[1]) {
    return allFiles.value
  }
  const startDate = new Date(dateRange.value[0])
  startDate.setHours(0, 0, 0, 0)
  const endDate = new Date(dateRange.value[1])
  endDate.setHours(23, 59, 59, 999)

  return allFiles.value.filter((file) => {
    const fileDate = new Date(file.exif_time || file.created_at)
    return fileDate >= startDate && fileDate <= endDate
  })
})

const groupedPhotos = computed<MonthGroup[]>(() => {
  const groups = new Map<string, MonthGroup>()

  for (const file of filteredFiles.value) {
    const dateStr = file.exif_time || file.created_at
    if (!dateStr) continue
    const date = new Date(dateStr)
    const year = date.getFullYear()
    const month = date.getMonth() + 1
    const key = `${year}-${month}`

    if (!groups.has(key)) {
      groups.set(key, { year, month, files: [] })
    }
    groups.get(key)!.files.push(file)
  }

  // Sort groups by year desc, month desc
  return Array.from(groups.values()).sort((a, b) => {
    if (a.year !== b.year) return b.year - a.year
    return b.month - a.month
  })
})

const timeNavData = computed<YearNavItem[]>(() => {
  const yearMap = new Map<number, { totalCount: number; months: Map<number, number> }>()

  for (const group of groupedPhotos.value) {
    if (!yearMap.has(group.year)) {
      yearMap.set(group.year, { totalCount: 0, months: new Map() })
    }
    const yearData = yearMap.get(group.year)!
    yearData.totalCount += group.files.length
    yearData.months.set(group.month, group.files.length)
  }

  return Array.from(yearMap.entries())
    .sort(([a], [b]) => b - a)
    .map(([year, data]) => ({
      year,
      totalCount: data.totalCount,
      months: Array.from(data.months.entries())
        .sort(([a], [b]) => b - a)
        .map(([month, count]) => ({ month, count })),
    }))
})

const totalPhotos = computed(() => filteredFiles.value.length)

// --- Methods ---
function setSectionRef(year: number, month: number, el: HTMLElement | null) {
  const key = `${year}-${month}`
  if (el) {
    sectionRefs.value.set(key, el)
  } else {
    sectionRefs.value.delete(key)
  }
}

function toggleYear(year: number) {
  if (expandedYears.value.has(year)) {
    expandedYears.value.delete(year)
  } else {
    expandedYears.value.add(year)
  }
  // Trigger reactivity
  expandedYears.value = new Set(expandedYears.value)
}

function scrollToSection(year: number, month: number) {
  activeYear.value = year
  activeMonth.value = month
  const key = `${year}-${month}`
  const el = sectionRefs.value.get(key)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function handleDateRangeChange() {
  // Reset navigation state when filter changes
  activeYear.value = null
  activeMonth.value = null
  // Auto-expand first year if data exists
  nextTick(() => {
    if (timeNavData.value.length > 0) {
      expandedYears.value = new Set([timeNavData.value[0].year])
    }
  })
}

function openPreview(group: MonthGroup, fileIndex: number) {
  // Collect all files for preview navigation
  previewFiles.value = group.files
  previewIndex.value = fileIndex
  previewVisible.value = true
}

function formatShortDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

function handleThumbnailError(e: Event) {
  const img = e.target as HTMLImageElement
  img.src =
    'data:image/svg+xml,' +
    encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect fill="#f0f0f0" width="200" height="200"/><text x="100" y="100" text-anchor="middle" fill="#999" font-size="14">无缩略图</text></svg>'
    )
}

async function loadAllFiles() {
  loading.value = true
  try {
    // Load all files sorted by time, paginating through all pages
    const allLoaded: FileInfo[] = []
    let page = 1
    const pageSize = 200
    let hasMore = true

    while (hasMore) {
      const response = await listFiles('', page, pageSize, 'time')
      allLoaded.push(...response.files)
      if (allLoaded.length >= response.total_files || response.files.length < pageSize) {
        hasMore = false
      } else {
        page++
      }
    }

    allFiles.value = allLoaded

    // Auto-expand the first year
    if (timeNavData.value.length > 0) {
      expandedYears.value = new Set([timeNavData.value[0].year])
    }
  } catch (error) {
    console.error('Failed to load timeline files:', error)
    allFiles.value = []
  } finally {
    loading.value = false
  }
}

// --- Lifecycle ---
onMounted(() => {
  loadAllFiles()
})
</script>

<style scoped>
.timeline-view {
  height: 100%;
}

.timeline-container {
  height: 100%;
}

/* Time sidebar */
.time-sidebar {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
}

.sidebar-header {
  padding: 12px 16px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  gap: 8px;
}

.time-nav {
  padding: 8px 0;
}

.year-group {
  margin-bottom: 2px;
}

.year-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.2s;
  user-select: none;
}

.year-item:hover {
  background: #f5f7fa;
}

.year-item.active {
  background: #ecf5ff;
  color: var(--el-color-primary);
}

.expand-icon {
  font-size: 12px;
  transition: transform 0.2s;
  margin-right: 6px;
  color: #909399;
}

.expand-icon.expanded {
  transform: rotate(90deg);
}

.year-label {
  font-size: 14px;
  font-weight: 500;
  flex: 1;
}

.year-count {
  font-size: 12px;
  color: #909399;
  background: #f0f2f5;
  padding: 1px 6px;
  border-radius: 10px;
}

.month-list {
  padding-left: 20px;
}

.month-item {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  cursor: pointer;
  transition: background 0.2s;
  border-radius: 4px;
  margin: 1px 8px;
}

.month-item:hover {
  background: #f5f7fa;
}

.month-item.active {
  background: #ecf5ff;
  color: var(--el-color-primary);
}

.month-label {
  font-size: 13px;
  flex: 1;
}

.month-count {
  font-size: 12px;
  color: #909399;
}

/* Expand transition */
.expand-enter-active,
.expand-leave-active {
  transition: all 0.2s ease;
  overflow: hidden;
}

.expand-enter-from,
.expand-leave-to {
  opacity: 0;
  max-height: 0;
}

.expand-enter-to,
.expand-leave-from {
  opacity: 1;
  max-height: 500px;
}

/* Content area */
.content-area {
  padding: 16px;
  overflow-y: auto;
}

/* Toolbar */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 10px 16px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-title {
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}

.photo-total {
  font-size: 13px;
  color: #909399;
  margin-left: 8px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
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

/* Photo groups */
.photos-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.photo-group {
  scroll-margin-top: 16px;
}

.group-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.group-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin: 0;
}

.group-count {
  font-size: 13px;
  color: #909399;
}

/* Photos grid */
.photos-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
}

.photo-card {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e4e7ed;
  transition: all 0.2s;
  aspect-ratio: 1;
}

.photo-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.photo-card:hover .photo-overlay {
  opacity: 1;
}

.photo-thumbnail {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.photo-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 8px 10px;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.7));
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.photo-name {
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-date {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.8);
}
</style>
