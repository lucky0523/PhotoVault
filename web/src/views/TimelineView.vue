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
        <div class="pv-page-head">
          <!-- Top toolbar: Date range filter -->
          <PageHeader title="时间线" :icon="Timer">
            <template #subtitle>
              <span v-if="hasActiveFilters" class="filter-badge">
                <el-icon><Filter /></el-icon>
                已筛选 {{ activeFilterCount }} 项 · {{ totalPhotos }}/{{ allFiles.length }} 张
              </span>
              <span v-else-if="totalPhotos > 0" class="pv-muted">共 {{ totalPhotos }} 张照片</span>
            </template>
            <template #extra>
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
              <!-- el-date-picker 的根元素是它内部 el-tooltip 渲染出来的，拿不到
                   本组件的 scoped 属性（所以此前直接写 .el-date-editor 的样式一直
                   没生效）。这里套一层自己的 div，再用 :deep() 定宽和加高亮，
                   否则 Element 的 flex-grow 会把控件拉满整行。 -->
              <div
                class="date-filter"
                :class="{ 'is-active': !!(dateRange && dateRange[0]) }"
              >
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
                  popper-class="tl-date-popper"
                  :cell-class-name="dateCellClass"
                  @change="handleFilterChange"
                />
              </div>
            </template>
          </PageHeader>
        </div>

        <div class="pv-page-body">
          <!-- Loading state -->
          <div v-if="loading" class="pv-loading">
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
                <h3 class="pv-group-title">{{ group.year }}年{{ group.month }}月</h3>
                <span class="pv-muted">{{ group.files.length }} 张</span>
              </div>
              <div class="pv-photo-grid">
                <div
                  v-for="(file, fileIndex) in group.files"
                  :key="file.id"
                  class="pv-tile"
                  @click="openPreview(group, fileIndex)"
                  @contextmenu="showContextMenu($event, file)"
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
                    <span class="pv-tile__meta">{{ formatShortDate(file.exif_time || file.created_at) }}</span>
                  </div>
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
        class="pv-context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <div class="pv-context-menu__item" @click="handleContextDownload">
          <el-icon><Download /></el-icon>
          下载
        </div>
        <div class="pv-context-menu__item is-danger" @click="handleContextDelete">
          <el-icon><Delete /></el-icon>
          移入回收站
        </div>
      </div>
      <div v-if="contextMenu.visible" class="pv-context-menu__overlay" @click="closeContextMenu" />
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
import { Timer, ArrowRight, Loading, Download, Delete, Filter, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAllFiles, getThumbnailUrl, downloadFile, deleteFile } from '@/api/files'
import type { FileInfo } from '@/api/files'
import ImagePreview from '@/components/ImagePreview.vue'
import LivePhotoIcon from '@/components/LivePhotoIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import { isVideo, isMotionPhoto, handleThumbnailError } from '@/utils/media'
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

// Set of local dates (YYYY-MM-DD) that actually have at least one photo/video.
// Used to dim (but not disable) empty days in the date picker.
const photoDates = computed<Set<string>>(() => {
  const set = new Set<string>()
  for (const f of allFiles.value) {
    const d = new Date(f.exif_time || f.created_at)
    if (!isNaN(d.getTime())) set.add(toLocalYmd(d))
  }
  return set
})

function toLocalYmd(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * Dims future days and days without any photos in the date picker, while
 * keeping every cell clickable (we intentionally avoid `disabled-date`).
 */
function dateCellClass(date: Date): string {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)

  if (d.getTime() > today.getTime()) {
    return 'tl-dim tl-future'
  }
  if (!photoDates.value.has(toLocalYmd(date))) {
    return 'tl-dim tl-empty'
  }
  return ''
}

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
/* 照片瓦片（pv-tile）、右键菜单（pv-context-menu）、加载态（pv-loading）、
   分组标题（pv-group-title）均来自 styles/layout.css，本页只保留时间轴侧栏
   与筛选器这些独有的样式。 */
.timeline-view,
.timeline-container {
  height: 100%;
}

/* Time sidebar */
.time-sidebar {
  background: #fff;
  border-right: 1px solid var(--pv-divider-color);
  overflow-y: auto;
}

.sidebar-header {
  padding: 12px 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  border-bottom: 1px solid var(--pv-divider-color);
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
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.expand-icon {
  font-size: 12px;
  transition: transform 0.2s;
  margin-right: 6px;
  color: var(--el-text-color-secondary);
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
  color: var(--el-text-color-secondary);
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
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.month-label {
  font-size: 13px;
  flex: 1;
}

.month-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
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

/* 右侧内容区：标题栏固定，照片自己滚动（pv-page-head / pv-page-body） */
.content-area {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* Filters：宽度取到「1400px 视口下标题栏仍是一行」的上限，
   更窄的窗口再靠 flex-wrap 换行降级。 */
.filter-select {
  width: 140px;
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

/* 日期区间控件：Element 的 .el-input__wrapper 带 flex-grow:1，在标题栏的
   flex 容器里会被拉满整行，这里固定宽度让四个筛选器排成一行。 */
.date-filter {
  flex: 0 0 auto;
  width: 280px;
}

.date-filter :deep(.el-date-editor) {
  width: 100%;
}

/* Highlight the date range picker when a range is set */
.date-filter.is-active :deep(.el-date-editor) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
  background-color: var(--el-color-primary-light-9);
  border-radius: var(--el-border-radius-base);
}

.clear-filter-btn {
  font-weight: 500;
}

/* Active-filter badge in the page header */
.filter-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 12px;
}

/* Photo groups */
.photos-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.photo-group {
  scroll-margin-top: var(--pv-page-gutter);
}

.group-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--pv-divider-color);
}
</style>

<!-- Non-scoped: the date-picker popup is teleported to <body>, so scoped
     styles can't reach it. Targeted via the custom popper-class instead. -->
<style>
/* Dim future days and days without photos, but keep them clickable.
   Don't override the selected/range cells' own text styling. */
.tl-date-popper .el-date-table td.tl-dim:not(.current):not(.start-date):not(.end-date):not(.in-range) .el-date-table-cell__text {
  color: #c8ccd4;
}

.tl-date-popper .el-date-table td.tl-dim.in-range:not(.current):not(.start-date):not(.end-date) .el-date-table-cell__text {
  color: #a9b0bd;
}

/* Ensure the cursor still indicates the cell is clickable. */
.tl-date-popper .el-date-table td.tl-dim .el-date-table-cell {
  cursor: pointer;
}
</style>
