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
            <span v-if="hasActiveFilters" class="filter-badge">
              <el-icon><Filter /></el-icon>
              已筛选 {{ activeFilterCount }} 项 · {{ totalPhotos }}/{{ allFiles.length }} 张
            </span>
            <span v-else-if="totalPhotos > 0" class="photo-total">共 {{ totalPhotos }} 张照片</span>
          </div>
          <div class="toolbar-right">
            <el-select
              v-model="selectedDevices"
              multiple
              collapse-tags
              collapse-tags-tooltip
              clearable
              placeholder="设备"
              :class="['filter-select', { 'filter-active': selectedDevices.length > 0 }]"
              size="default"
              @change="handleFilterChange"
            >
              <el-option
                v-for="d in deviceOptions"
                :key="d"
                :label="d"
                :value="d"
              />
            </el-select>
            <el-select
              v-model="selectedFormats"
              multiple
              collapse-tags
              collapse-tags-tooltip
              clearable
              placeholder="文件格式"
              :class="['filter-select', { 'filter-active': selectedFormats.length > 0 }]"
              size="default"
              @change="handleFilterChange"
            >
              <el-option
                v-for="fmt in formatOptions"
                :key="fmt"
                :label="fmt"
                :value="fmt"
              />
            </el-select>
            <el-select
              v-model="selectedFocals"
              multiple
              collapse-tags
              collapse-tags-tooltip
              clearable
              placeholder="焦段"
              :class="['filter-select', { 'filter-active': selectedFocals.length > 0 }]"
              size="default"
              @change="handleFilterChange"
            >
              <el-option
                v-for="b in focalOptions"
                :key="b.key"
                :label="b.label"
                :value="b.key"
              />
            </el-select>
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
              :class="{ 'filter-active': !!(dateRange && dateRange[0]) }"
              @change="handleFilterChange"
            />
            <el-button
              v-if="hasActiveFilters"
              type="primary"
              :icon="RefreshLeft"
              text
              class="clear-filter-btn"
              @click="resetFilters"
            >
              清除筛选
            </el-button>
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
                @contextmenu="showContextMenu($event, file)"
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

    <!-- Right-click context menu -->
    <teleport to="body">
      <div
        v-if="contextMenu.visible"
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <div class="context-menu-item" @click="handleContextDownload">
          <el-icon><Download /></el-icon>
          下载
        </div>
        <div class="context-menu-item danger" @click="handleContextDelete">
          <el-icon><Delete /></el-icon>
          移入回收站
        </div>
      </div>
      <div v-if="contextMenu.visible" class="context-menu-overlay" @click="closeContextMenu" />
    </teleport>

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
import { Timer, ArrowRight, Calendar, Loading, Download, Delete, Filter, RefreshLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAllFiles, getThumbnailUrl, downloadFile, deleteFile } from '@/api/files'
import type { FileInfo } from '@/api/files'
import ImagePreview from '@/components/ImagePreview.vue'
import { useTrashStore } from '@/stores/trash'
import { useConfigStore } from '@/stores/config'

const trashStore = useTrashStore()
const configStore = useConfigStore()

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
const selectedDevices = ref<string[]>([])
const selectedFormats = ref<string[]>([])
const selectedFocals = ref<string[]>([])
const expandedYears = ref<Set<number>>(new Set())
const activeYear = ref<number | null>(null)
const activeMonth = ref<number | null>(null)

// Focal-length buckets (焦段). Ordered from wide to tele; "unknown" last.
const FOCAL_BUCKETS = [
  { key: 'ultrawide', label: '超广角 (≤20mm)' },
  { key: 'wide', label: '广角 (21-35mm)' },
  { key: 'standard', label: '标准 (36-70mm)' },
  { key: 'tele', label: '中长焦 (71-135mm)' },
  { key: 'supertele', label: '长焦 (>135mm)' },
  { key: 'unknown', label: '未知焦段' },
]

function focalBucketKey(focal?: number | null): string {
  if (focal == null || focal <= 0) return 'unknown'
  if (focal <= 20) return 'ultrawide'
  if (focal <= 35) return 'wide'
  if (focal <= 70) return 'standard'
  if (focal <= 135) return 'tele'
  return 'supertele'
}

function fileExt(name: string): string {
  const i = name.lastIndexOf('.')
  if (i < 0 || i === name.length - 1) return ''
  return name.slice(i + 1).toUpperCase()
}

// Preview state
const previewVisible = ref(false)
const previewFiles = ref<FileInfo[]>([])
const previewIndex = ref(0)

// Context menu state
interface ContextMenuState {
  visible: boolean
  x: number
  y: number
  file: FileInfo | null
}
const contextMenu = ref<ContextMenuState>({
  visible: false,
  x: 0,
  y: 0,
  file: null,
})

// Section refs for scrolling
const sectionRefs = ref<Map<string, HTMLElement>>(new Map())
const photosContentRef = ref<HTMLElement | null>(null)

// --- Computed ---
const deviceOptions = computed<string[]>(() => {
  const set = new Set<string>()
  for (const f of allFiles.value) {
    if (f.device_name) set.add(f.device_name)
  }
  return Array.from(set).sort()
})

const formatOptions = computed<string[]>(() => {
  const set = new Set<string>()
  for (const f of allFiles.value) {
    const ext = fileExt(f.file_name)
    if (ext) set.add(ext)
  }
  return Array.from(set).sort()
})

const focalOptions = computed(() => {
  const present = new Set<string>()
  for (const f of allFiles.value) {
    present.add(focalBucketKey(f.focal_length))
  }
  return FOCAL_BUCKETS.filter((b) => present.has(b.key))
})

const filteredFiles = computed(() => {
  let result = allFiles.value

  // Date range
  if (dateRange.value && dateRange.value[0] && dateRange.value[1]) {
    const startDate = new Date(dateRange.value[0])
    startDate.setHours(0, 0, 0, 0)
    const endDate = new Date(dateRange.value[1])
    endDate.setHours(23, 59, 59, 999)
    result = result.filter((file) => {
      const fileDate = new Date(file.exif_time || file.created_at)
      return fileDate >= startDate && fileDate <= endDate
    })
  }

  // Device
  if (selectedDevices.value.length > 0) {
    result = result.filter(
      (f) => f.device_name != null && selectedDevices.value.includes(f.device_name)
    )
  }

  // File format
  if (selectedFormats.value.length > 0) {
    result = result.filter((f) => selectedFormats.value.includes(fileExt(f.file_name)))
  }

  // Focal length bucket
  if (selectedFocals.value.length > 0) {
    result = result.filter((f) => selectedFocals.value.includes(focalBucketKey(f.focal_length)))
  }

  return result
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

const hasActiveFilters = computed(
  () =>
    selectedDevices.value.length > 0 ||
    selectedFormats.value.length > 0 ||
    selectedFocals.value.length > 0 ||
    !!(dateRange.value && dateRange.value[0])
)

const activeFilterCount = computed(() => {
  let n = 0
  if (selectedDevices.value.length > 0) n++
  if (selectedFormats.value.length > 0) n++
  if (selectedFocals.value.length > 0) n++
  if (dateRange.value && dateRange.value[0]) n++
  return n
})

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

function handleFilterChange() {
  // Reset navigation state when any filter changes
  activeYear.value = null
  activeMonth.value = null
  // Auto-expand first year if data exists
  nextTick(() => {
    if (timeNavData.value.length > 0) {
      expandedYears.value = new Set([timeNavData.value[0].year])
    }
  })
}

function resetFilters() {
  selectedDevices.value = []
  selectedFormats.value = []
  selectedFocals.value = []
  dateRange.value = null
  handleFilterChange()
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

function showContextMenu(event: MouseEvent, file: FileInfo) {
  event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    file,
  }
}

function closeContextMenu() {
  contextMenu.value.visible = false
  contextMenu.value.file = null
}

function handleContextDownload() {
  if (contextMenu.value.file) {
    downloadFile(contextMenu.value.file.id, contextMenu.value.file.file_name)
  }
  closeContextMenu()
}

async function handleContextDelete() {
  const file = contextMenu.value.file
  closeContextMenu()
  if (!file) return
  try {
    await ElMessageBox.confirm(
      `确定要将文件 "${file.file_name}" 移入回收站吗？\n可在回收站中恢复，${configStore.trashRetentionDays} 天后自动彻底删除。`,
      '移入回收站',
      {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
    const result = await deleteFile(file.id)
    ElMessage.success(result.message)
    // Remove from local state so the timeline updates without a full reload
    allFiles.value = allFiles.value.filter((f) => f.id !== file.id)
    trashStore.refresh()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('移入回收站失败')
    }
  }
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
      const response = await listAllFiles(page, pageSize, 'time')
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
  configStore.ensureLoaded()
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
  flex-wrap: wrap;
  gap: 12px;
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
  flex-wrap: wrap;
  justify-content: flex-end;
}

.filter-select {
  width: 160px;
}

/* Bright-blue selected tags inside the filter dropdowns */
.filter-select :deep(.el-tag) {
  background-color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  color: #fff;
}

.filter-select :deep(.el-tag .el-tag__close) {
  color: #fff;
}

.filter-select :deep(.el-tag .el-tag__close:hover) {
  background-color: rgba(255, 255, 255, 0.35);
  color: #fff;
}

/* Highlight a filter box when it has an active selection */
.filter-select.filter-active :deep(.el-select__wrapper),
.filter-select.filter-active :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
  background-color: #fff;
}

/* Highlight the date range picker when a range is set */
.el-date-editor.filter-active {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
  background-color: var(--el-color-primary-light-9);
  border-radius: var(--el-border-radius-base);
}

.clear-filter-btn {
  font-weight: 500;
}

/* Active-filter badge in the toolbar header */
.filter-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 8px;
  padding: 2px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 12px;
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

/* Context menu */
.context-menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 999;
}

.context-menu {
  position: fixed;
  z-index: 1000;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
  padding: 6px 0;
  min-width: 140px;
}

.context-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  font-size: 14px;
  color: #303133;
  cursor: pointer;
  transition: background 0.2s;
}

.context-menu-item:hover {
  background: #f5f7fa;
}

.context-menu-item.danger {
  color: #f56c6c;
}

.context-menu-item.danger:hover {
  background: #fef0f0;
}
</style>
