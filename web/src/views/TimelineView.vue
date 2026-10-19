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
          <!-- 标题栏：右侧是两行筛选栏。第 1 行：文件名搜索、目录、包含子目录、清除筛选；
               第 2 行：设备、文件格式、焦段、日期范围、拍摄日期标签。全部筛选状态都在 URL 里。 -->
          <PageHeader title="时间线" :icon="Timer">
            <template #subtitle>
              <span v-if="hasActiveFilters" class="filter-badge">
                <el-icon><Filter /></el-icon>
                已筛选 {{ activeFilterCount }} 项 · {{ totalPhotos }}/{{ libraryTotal }} 张
              </span>
              <span v-else-if="totalPhotos > 0" class="pv-muted">共 {{ totalPhotos }} 张照片</span>
            </template>
            <template #extra>
              <div class="tl-filters">
                <!-- 第 1 行：文件名搜索、目录、包含子目录、清除筛选 -->
                <div class="tl-filter-row tl-filter-row--primary">
                  <!-- 文件名模糊搜索。只有按回车才搜索（打字过程中不访问服务端），
                       点输入框的清除按钮立即恢复全部结果。搜索在服务端执行，长度上限与服务端一致。
                       “搜索文件名，回车搜索”在 200px 宽的输入框里放不下（需 140px，只有 134px），
                       所以占位文字保持简短，回车提示放在 title 里。 -->
                  <el-input
                    v-model="searchInput"
                    :prefix-icon="Search"
                    clearable
                    maxlength="200"
                    placeholder="搜索文件名"
                    title="输入文件名后按回车搜索"
                    aria-label="搜索文件名"
                    :class="['filename-search', { 'is-active': searchQuery !== '' }]"
                    size="default"
                    @clear="applySearchNow"
                    @keydown.enter="handleSearchEnter"
                  />
                  <!-- 目录筛选：与数据统计的目录范围同语义，'' = 全部（含自定义存储路径） -->
                  <div class="dir-filter" :class="{ 'is-active': dirPath !== '' }">
                    <DirectoryTreeSelect aria-label="选择目录" :path="dirPath" @update:path="onDirPathChange" />
                  </div>
                  <el-tooltip
                    content="根目录始终包含全部文件"
                    placement="bottom"
                    :disabled="dirPath !== ''"
                  >
                    <span class="recursive-switch">
                      <el-switch
                        :model-value="dirPath === '' ? true : dirRecursive"
                        :disabled="dirPath === ''"
                        active-text="包含子目录"
                        aria-label="包含子目录"
                        @update:model-value="onDirRecursiveChange"
                      />
                    </span>
                  </el-tooltip>
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
                <!-- 第 2 行：设备、文件格式、焦段、日期范围、拍摄日期 -->
                <div class="tl-filter-row tl-filter-row--secondary">
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
                  <!-- 单日拍摄日期：只能经 URL 的 day 设置（数据统计热力图点击日期跳转过来），可关闭 -->
                  <el-tooltip
                    v-if="captureDay"
                    content="按数据统计口径（拍摄时间、当地时间）筛选"
                    placement="bottom"
                  >
                    <el-tag
                      class="capture-day-tag"
                      closable
                      disable-transitions
                      size="large"
                      @close="clearCaptureDay"
                    >
                      拍摄日期：{{ formatCaptureDay(captureDay) }}
                    </el-tag>
                  </el-tooltip>
                </div>
              </div>
            </template>
          </PageHeader>
        </div>

        <!-- 滚动容器。它不在任何 v-if 里，尺寸测量（ResizeObserver）和滚动监听都挂在它上面。 -->
        <div ref="pageBodyRef" class="pv-page-body">
          <!-- 测量分组标题高度用，不可见；放在 v-if 链外面，保证任何状态下都存在 -->
          <div ref="headerProbeRef" class="group-header header-probe" aria-hidden="true">
            <h3 class="pv-group-title">2000年12月</h3>
            <span class="pv-muted">0 张</span>
          </div>

          <!-- Loading state -->
          <div v-if="loading && !loaded" class="pv-loading">
            <el-icon class="is-loading" :size="32"><Loading /></el-icon>
            <span>加载中...</span>
          </div>

          <!-- 首次加载失败：没有任何内容可显示时给出重试入口，而不是空白 -->
          <el-empty v-else-if="!loaded && loadFailed" description="加载时间线失败">
            <el-button type="primary" @click="retryLoad">重试</el-button>
          </el-empty>

          <!-- Empty state -->
          <el-empty
            v-else-if="months.length === 0"
            :description="hasActiveFilters ? '没有符合筛选条件的照片' : '暂无备份图片'"
          />

          <!-- 按年月分组的时间线。高度由布局算出，只渲染视口上下各一屏内的行。 -->
          <div
            v-else
            ref="photosContentRef"
            class="photos-content"
            :style="{ height: layout.totalHeight + 'px' }"
          >
            <div
              v-for="section in renderedSections"
              :key="section.entry.key"
              class="photo-group"
              :style="{ top: section.top + 'px', height: section.height + 'px' }"
            >
              <div class="group-header">
                <h3 class="pv-group-title">{{ section.entry.year }}年{{ section.entry.month }}月</h3>
                <span class="pv-muted">{{ section.entry.count }} 张</span>
              </div>
              <div class="pv-photo-grid" :style="{ top: section.gridTop + 'px' }">
                <template v-for="tile in section.tiles" :key="tile.key">
                  <div
                    v-if="tile.file"
                    class="pv-tile"
                    @click="openPreview(section.entry, tile.index)"
                    @contextmenu="onTileContextMenu($event, tile, section.entry.key)"
                  >
                    <div class="pv-tile__media">
                      <img
                        :src="getThumbnailUrl(tile.file.id, 'small')"
                        :alt="tile.file.file_name"
                        loading="lazy"
                        @error="handleThumbnailError"
                      />
                      <div v-if="isVideo(tile.file)" class="pv-tile__video">
                        <el-icon :size="28"><VideoPlay /></el-icon>
                      </div>
                      <div v-else-if="isMotionPhoto(tile.file)" class="pv-tile__live">
                        <LivePhotoIcon class="pv-tile__live-icon" />
                        <span>LIVE</span>
                      </div>
                      <div v-if="tile.file.is_ultra_hdr" class="pv-tile__hdr" title="Ultra HDR">HDR</div>
                    </div>
                    <div class="pv-tile__overlay">
                      <span class="pv-tile__name">{{ tile.file.file_name }}</span>
                      <span class="pv-tile__meta">{{ formatShortDate(tile.file.exif_time || tile.file.created_at) }}</span>
                    </div>
                  </div>
                  <!-- 还没加载到的位置：占位瓦片，不可点击、没有右键菜单 -->
                  <div v-else class="pv-tile is-placeholder" aria-hidden="true" />
                </template>
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
      @index-change="handlePreviewIndexChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import axios from 'axios'
import { Timer, ArrowRight, Loading, Download, Delete, Filter, RefreshLeft, VideoPlay, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getThumbnailUrl,
  downloadFile,
  deleteFile,
  normalizeTimestamp,
  getTimelineFacets,
  getTimelineSummary,
  listTimelineFiles,
  isInvalidTzError,
} from '@/api/files'
import type { FileInfo, TimelineFacets, TimelineFilesPage, TimelinePageRequest, TimelineQuery } from '@/api/files'
import ImagePreview from '@/components/ImagePreview.vue'
import LivePhotoIcon from '@/components/LivePhotoIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect.vue'
import { isVideo, isMotionPhoto, handleThumbnailError } from '@/utils/media'
import { buildLayout, findVisibleSections, missingRuns } from '@/utils/timelineLayout'
import type { SectionLayout, TimelineLayout } from '@/utils/timelineLayout'
import { useTrashStore } from '@/stores/trash'
import { useConfigStore } from '@/stores/config'

const trashStore = useTrashStore()
const configStore = useConfigStore()
const route = useRoute()
const router = useRouter()

// 为什么按月份请求：照片很容易上万张，整库拉到浏览器里既慢又占内存。服务端先给出
// 每个月的张数（summary），页面据此铺出整条时间线的骨架；某个月滚进视口附近时，
// 再按月份分页请求这一段的文件（files）。搜索与筛选（含目录、单日拍摄日期）都由服务端执行。
//
// 全部筛选状态都写在 URL 里（可分享的深链接、浏览器前进 / 后退）：
//   q、device / format / focal（可重复）、from + to（日期范围）、day（拍摄日期）、
//   path（目录）、recursive=0（不含子目录，只在 path 非空时出现）。
// timelineFiltersFromRoute 是 URL → 筛选状态的唯一归一化入口（宽松：非法值直接丢弃）；
// 控件变化时 router.replace 写回 URL（不堆历史）并立即重新请求一次；
// watch(route.query) 只在归一化后的状态与当前不同时（深链接、前进 / 后退）才应用并重新请求。

// --- Types ---
interface YearNavItem {
  year: number
  totalCount: number
  months: { month: number; count: number }[]
}

/**
 * 一个月份区块。不可变：任何改动都生成新对象，并替换 months.value。
 * 布局、时间轴、计数、可见瓦片、预览数组都只从 months 派生，所以只要所有改动都
 * 经过 commitMonth，就不会出现“数据改了、高度或计数没跟着重算”的情况。
 */
interface MonthEntry {
  readonly key: string // 'YYYY-MM'
  readonly year: number
  readonly month: number
  readonly count: number // 本月行数，恒等于 items.length
  readonly items: ReadonlyArray<FileInfo | undefined> // 未加载的位置是空洞
}

/** 每个月的加载调度状态。不参与渲染，放普通 Map。 */
interface MonthLoadState {
  inflight: AbortController | null
  epoch: number // 本地删除时 +1；响应回来时不一致就丢弃
  failedAt: number // 最近一次加载失败的时间戳，0 = 无
  keysetResets: number // 本次查询里因 keyset 冲突整月清空的次数（有上限，见 applyPage）
}

/** 一次 files 请求发出时的上下文，响应回来时据此判断是否过期。 */
interface PageRequestContext {
  generation: number
  epoch: number
  s: number
  keyset: boolean // 以前一格为游标续页（否则是月内 offset）
}

interface WantedRange {
  key: string
  from: number
  to: number
  priority: 0 | 1
  distance: number
}

interface RenderedTile {
  key: number | string
  index: number
  file: FileInfo | undefined
}

// --- 查询状态（标题栏控件） ---
const dateRange = ref<[string, string] | null>(null)
const selectedDevices = ref<string[]>([])
const selectedFormats = ref<string[]>([])
const selectedFocals = ref<string[]>([])
// 文件名搜索：searchInput 绑定输入框，按回车（或点清除按钮）时才写入 searchQuery；
// 请求、描边和“已筛选”徽标都只读 searchQuery，打字过程中不请求服务端。
const searchInput = ref('')
const searchQuery = ref('')
// 单日拍摄日期 YYYY-MM-DD（'' = 不筛选），只能经 URL 的 day 设置，标签可关闭
const captureDay = ref('')
// 目录筛选：'' = 全部；根目录时包含子目录恒为 true
const dirPath = ref('')
const dirRecursive = ref(true)
const expandedYears = ref<Set<number>>(new Set())
const activeYear = ref<number | null>(null)
const activeMonth = ref<number | null>(null)

// 浏览器时区随请求发给服务端，月份归属与页面显示一致；服务端不认识时回退一次（不带 tz）
let timeZone: string | undefined = Intl.DateTimeFormat().resolvedOptions().timeZone || undefined
let tzFallbackDone = false

// Focal-length buckets (焦段). Ordered from wide to tele; "unknown" last.
const FOCAL_BUCKETS = [
  { key: 'ultrawide', label: '超广角 (≤20mm)' },
  { key: 'wide', label: '广角 (21-35mm)' },
  { key: 'standard', label: '标准 (36-70mm)' },
  { key: 'tele', label: '中长焦 (71-135mm)' },
  { key: 'supertele', label: '长焦 (>135mm)' },
  { key: 'unknown', label: '未知焦段' },
]

// --- 服务端数据 ---
const facets = shallowRef<TimelineFacets | null>(null)
const libraryTotal = ref(0) // 徽标里的 y（summary.total_count，本地删除/对账时增减）
const loading = ref(true) // 首个 summary 返回前为 true；初值就是 true，避免挂载前一帧落到空白分支
const loadFailed = ref(false) // 首次 summary 失败、没有任何内容可显示
const loaded = ref(false) // 至少装入过一次 summary（区分“加载中”和“空”）

const months = shallowRef<readonly MonthEntry[]>([]) // 年月倒序
let monthsGeneration = 0 // months 属于哪一次查询
const loadStates = new Map<string, MonthLoadState>()
let queryGeneration = 0 // 每次重新查询 +1；所有响应回来时比对，不等就丢弃
let facetsSeq = 0
let appliedQuery: TimelineQuery = {} // 当前 months 对应的查询，files 请求沿用它
let summaryController: AbortController | null = null
let facetsController: AbortController | null = null
const inflightLoads = new Set<AbortController>()

const MAX_CONCURRENT_LOADS = 2
const FAILURE_BACKOFF_MS = 3000
const PAGE_LIMIT = 200
const LARGE_PAGE_LIMIT = 500
const MAX_KEYSET_RESETS = 3 // 见 applyPage：keyset 冲突时整月清空重拉的次数上限
const GROUP_GAP = 24 // 分组间距（原 .photos-content 的 gap）

// --- 布局测量（全部基于常驻的 .pv-page-body） ---
const pageBodyRef = ref<HTMLElement | null>(null)
const headerProbeRef = ref<HTMLElement | null>(null)
const photosContentRef = ref<HTMLElement | null>(null)
const contentWidth = ref(0)
const viewportHeight = ref(0)
const headerBlock = ref(0)
const tileMin = ref(180)
const gridGap = ref(12)
const bodyPaddingBottom = ref(16)
const scrollTop = ref(0)

const layout = computed<TimelineLayout>(() =>
  buildLayout(
    months.value,
    contentWidth.value,
    viewportHeight.value,
    headerBlock.value,
    tileMin.value,
    gridGap.value,
    GROUP_GAP,
    bodyPaddingBottom.value
  )
)

// Preview state：预览数组就是该月的稀疏 items，月份被 commitMonth 替换时自动更新
const previewVisible = ref(false)
const previewMonthKey = ref<string | null>(null)
const previewIndex = ref(0)
const previewFiles = computed<ReadonlyArray<FileInfo | undefined>>(
  () => months.value.find((m) => m.key === previewMonthKey.value)?.items ?? []
)
let previewDemand: { monthKey: string; from: number; to: number } | null = null // 打开预览/翻页时更新，关闭时置 null

// Context menu state
interface ContextMenuState {
  visible: boolean
  x: number
  y: number
  file: FileInfo | null
  monthKey: string | null
  generation: number // 打开菜单时的 queryGeneration
}
const contextMenu = ref<ContextMenuState>({
  visible: false,
  x: 0,
  y: 0,
  file: null,
  monthKey: null,
  generation: 0,
})

// --- Computed ---
const deviceOptions = computed<string[]>(() => [...(facets.value?.devices ?? [])].sort())
const formatOptions = computed<string[]>(() => [...(facets.value?.formats ?? [])].sort())
const focalOptions = computed(() => {
  const present = facets.value?.focal_buckets ?? []
  return FOCAL_BUCKETS.filter((b) => present.includes(b.key))
})

// Set of local dates (YYYY-MM-DD) that actually have at least one photo/video.
// Used to dim (but not disable) empty days in the date picker.
const photoDates = computed<Set<string>>(() => new Set(facets.value?.photo_dates ?? []))

const timeNavData = computed<YearNavItem[]>(() => {
  const years: YearNavItem[] = []
  for (const m of months.value) {
    let last = years[years.length - 1]
    if (!last || last.year !== m.year) {
      last = { year: m.year, totalCount: 0, months: [] }
      years.push(last)
    }
    last.totalCount += m.count
    last.months.push({ month: m.month, count: m.count })
  }
  return years
})

// 等于 summary.filtered_count；删除、对账后随 months 自动重算
const totalPhotos = computed(() => months.value.reduce((n, m) => n + m.count, 0))

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
    !!(dateRange.value && dateRange.value[0]) ||
    searchQuery.value !== '' ||
    dirPath.value !== '' ||
    captureDay.value !== ''
)

const activeFilterCount = computed(() => {
  let n = 0
  if (searchQuery.value !== '') n++
  if (dirPath.value !== '') n++ // 目录 + 是否包含子目录算一项
  if (selectedDevices.value.length > 0) n++
  if (selectedFormats.value.length > 0) n++
  if (selectedFocals.value.length > 0) n++
  if (dateRange.value && dateRange.value[0]) n++
  if (captureDay.value !== '') n++
  return n
})

// --- 窗口化渲染 ---

function monthKeyOf(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}`
}

/** 内容区在滚动容器里的偏移；内容区还没渲染时用滚动容器的上内边距（两者相等）。 */
function contentOffsetTop(): number {
  const content = photosContentRef.value
  if (content) return content.offsetTop
  const body = pageBodyRef.value
  return body ? Number.parseFloat(getComputedStyle(body).paddingTop) || 0 : 0
}

function viewportH(): number {
  return viewportHeight.value || window.innerHeight
}

/** 可见窗口：视口本身加上上下各一屏（上方一屏、下方两屏的下沿）。 */
function currentWindow(top: number = scrollTop.value) {
  const vh = viewportH()
  const viewTop = top - contentOffsetTop()
  return { viewTop, from: viewTop - vh, to: viewTop + 2 * vh }
}

interface VisibleSlice {
  entry: MonthEntry
  sec: SectionLayout
  a: number // 本月第一个要渲染的下标
  b: number // 最后一个要渲染的下标 + 1
  r0: number // 第一行的行号
}

function visibleSlices(win: { from: number; to: number }): VisibleSlice[] {
  const L = layout.value
  if (contentWidth.value === 0 || L.sections.length === 0) return []
  const [start, end] = findVisibleSections(L, win.from, win.to)
  const slices: VisibleSlice[] = []
  for (let i = start; i < end; i++) {
    const sec = L.sections[i]
    const entry = months.value[i]
    if (!entry) continue
    const gridTop = sec.top + L.headerBlock
    const r0 = Math.min(sec.rows - 1, Math.max(0, Math.floor((win.from - gridTop) / L.pitch)))
    const r1 = Math.min(sec.rows - 1, Math.floor((win.to - gridTop) / L.pitch))
    const a = r0 * L.cols
    const b = r1 >= r0 ? Math.min(entry.count, (r1 + 1) * L.cols) : a
    slices.push({ entry, sec, a, b, r0 })
  }
  return slices
}

const renderedSections = computed(() => {
  const L = layout.value
  return visibleSlices(currentWindow()).map((slice) => {
    const tiles: RenderedTile[] = []
    for (let i = slice.a; i < slice.b; i++) {
      const file = slice.entry.items[i]
      tiles.push(file ? { key: file.id, index: i, file } : { key: `ph-${i}`, index: i, file: undefined })
    }
    return {
      entry: slice.entry,
      top: slice.sec.top,
      height: slice.sec.height,
      gridTop: L.headerBlock + slice.r0 * L.pitch,
      tiles,
    }
  })
})

function measureHeaderBlock() {
  const probe = headerProbeRef.value
  if (!probe) return
  headerBlock.value =
    probe.getBoundingClientRect().height + (Number.parseFloat(getComputedStyle(probe).marginBottom) || 0)
}

/** 用新数组替换 months.value；这是修改月份的唯一入口。next 为 null 或 count 为 0 时移除该月。 */
function commitMonth(key: string, next: MonthEntry | null) {
  const list = months.value
  const i = list.findIndex((m) => m.key === key)
  if (i < 0) return
  if (import.meta.env.DEV && next) {
    console.assert(next.items.length === next.count, 'timeline: items.length !== count', key)
  }
  const body = pageBodyRef.value
  const viewTop = (body ? body.scrollTop : scrollTop.value) - contentOffsetTop()
  const oldLayout = layout.value
  const oldSec = oldLayout.sections[i]
  const removed = !next || next.count === 0
  const copy = list.slice()
  if (removed) copy.splice(i, 1)
  else copy[i] = next
  months.value = copy

  // 位置锚定：整个在视口顶部之上的月份高度变化时，scrollTop 同步平移，视口里的内容不跳
  if (contentWidth.value > 0 && oldSec && oldSec.top + oldSec.height <= viewTop) {
    // 被移除时连同分组间距一起消失
    const newHeight = removed ? -oldLayout.groupGap : layout.value.sections[i].height
    const delta = newHeight - oldSec.height
    if (delta !== 0) {
      pendingScrollAdjust += delta
      queueScrollAdjust()
    }
  }
}

let pendingScrollAdjust = 0
let scrollAdjustQueued = false

/** DOM 更新之后、绘制之前再改 scrollTop，否则内容高度还没变，浏览器会按旧的 scrollHeight 截断。 */
function queueScrollAdjust() {
  if (scrollAdjustQueued) return
  scrollAdjustQueued = true
  void nextTick(() => {
    scrollAdjustQueued = false
    const body = pageBodyRef.value
    const delta = pendingScrollAdjust
    pendingScrollAdjust = 0
    if (!body || delta === 0) return
    body.scrollTop += delta
    scrollTop.value = body.scrollTop
  })
}

/** 列数变化前记下视口顶部所在的（月份、该行第一个下标）。 */
function captureAnchor(): { key: string; index: number } | null {
  const body = pageBodyRef.value
  const L = layout.value
  if (!body || contentWidth.value === 0 || L.sections.length === 0) return null
  const viewTop = body.scrollTop - contentOffsetTop()
  if (viewTop <= 0) return null
  const sec = L.sections[Math.max(0, lastSectionAtOrAbove(viewTop))]
  const gridTop = sec.top + L.headerBlock
  if (viewTop < gridTop) return { key: sec.key, index: -1 }
  const row = Math.min(sec.rows - 1, Math.floor((viewTop - gridTop) / L.pitch))
  return { key: sec.key, index: row * L.cols }
}

/** 重算布局后，把 scrollTop 设到锚点下标所在行的新位置。 */
function restoreAnchor(anchor: { key: string; index: number }) {
  const body = pageBodyRef.value
  const L = layout.value
  const i = months.value.findIndex((m) => m.key === anchor.key)
  if (!body || i < 0) return
  const sec = L.sections[i]
  const top = anchor.index < 0 ? sec.top : sec.top + L.headerBlock + Math.floor(anchor.index / L.cols) * L.pitch
  body.scrollTop = contentOffsetTop() + top
  scrollTop.value = body.scrollTop
}

/** 最后一个 top ≤ y 的月份下标；y 在第一个月之上时返回 -1。 */
function lastSectionAtOrAbove(y: number): number {
  const s = layout.value.sections
  let lo = 0
  let hi = s.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (s[mid].top <= y) lo = mid + 1
    else hi = mid
  }
  return lo - 1
}

/** 时间轴高亮跟随视口顶部所在的月份（只在变化时赋值）。 */
function updateActiveFromScroll() {
  if (months.value.length === 0 || contentWidth.value === 0) return
  // 视口顶部还在第一个月之上（页面最顶端）时保持现状
  const entry = months.value[lastSectionAtOrAbove(scrollTop.value - contentOffsetTop() + 1)]
  if (!entry) return
  if (activeYear.value !== entry.year) activeYear.value = entry.year
  if (activeMonth.value !== entry.month) activeMonth.value = entry.month
}

// --- 滚动与时间轴跳转 ---

let scrollRaf = 0

function handleScroll() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    const body = pageBodyRef.value
    if (!body) return
    scrollTop.value = body.scrollTop
    if (pendingJump) checkJumpArrival()
    else updateActiveFromScroll()
    scheduleEnsureVisible()
  })
}

/** 正在进行的时间轴跳转：只存月份 key，“是否到达”每次按当前布局重算目标位置。 */
let pendingJump: { key: string; startedAt: number; timer: ReturnType<typeof setTimeout> } | null = null
const JUMP_TIMEOUT_MS = 1500
const JUMP_CANCEL_EVENTS = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const

function sectionTargetTop(key: string): number | null {
  const i = months.value.findIndex((m) => m.key === key)
  if (i < 0) return null
  return contentOffsetTop() + layout.value.sections[i].top
}

function checkJumpArrival() {
  if (!pendingJump) return
  const body = pageBodyRef.value
  const target = sectionTargetTop(pendingJump.key)
  // 该月已被移除时视为到达
  if (!body || target === null) {
    clearPendingJump()
    return
  }
  const maxScrollTop = Math.max(0, body.scrollHeight - body.clientHeight)
  if (Math.abs(scrollTop.value - Math.min(target, maxScrollTop)) <= 2) {
    clearPendingJump(true)
  } else if (performance.now() - pendingJump.startedAt > JUMP_TIMEOUT_MS) {
    clearPendingJump()
  }
}

/**
 * 结束跳转（到达、用户接管或超时），并立即补齐可见数据。
 * 正常到达时高亮就是被点的月份（到达判定有 2px 容差，不能再按位置重算，否则会闪回上一个月）；
 * 其余情况按当前位置重算一次高亮。
 */
function clearPendingJump(arrived = false) {
  if (!pendingJump) return
  clearTimeout(pendingJump.timer)
  const key = pendingJump.key
  pendingJump = null
  const entry = arrived ? months.value.find((m) => m.key === key) : undefined
  if (entry) {
    activeYear.value = entry.year
    activeMonth.value = entry.month
  } else {
    updateActiveFromScroll()
  }
  ensureVisible()
}

function scrollToSection(year: number, month: number) {
  activeYear.value = year
  activeMonth.value = month
  const body = pageBodyRef.value
  const key = monthKeyOf(year, month)
  const target = sectionTargetTop(key)
  if (!body || target === null) return
  if (pendingJump) clearTimeout(pendingJump.timer)
  pendingJump = {
    key,
    startedAt: performance.now(),
    // 平滑滚动被浏览器中断时的兜底
    timer: setTimeout(clearPendingJump, JUMP_TIMEOUT_MS),
  }
  // 近距离保留平滑滚动的手感；远跳瞬时到达，不让滚动途经几十个月触发加载和缩略图请求
  const smooth = Math.abs(target - body.scrollTop) <= 3 * viewportH()
  body.scrollTo({ top: target, behavior: smooth ? 'smooth' : 'auto' })
  scrollTop.value = body.scrollTop
  checkJumpArrival()
}

// --- 按需加载（统一调度） ---

let ensureTimer: ReturnType<typeof setTimeout> | null = null
let ensureWaitStartedAt = 0
let retryTimer: ReturnType<typeof setTimeout> | null = null
const ENSURE_DEBOUNCE_MS = 80
const ENSURE_MAX_WAIT_MS = 250

/** 滚动中使用：尾随防抖 80ms、最长等待 250ms，快速拖滚动条时不为路过的月份发请求。 */
function scheduleEnsureVisible() {
  const now = performance.now()
  if (ensureTimer === null) ensureWaitStartedAt = now
  else clearTimeout(ensureTimer)
  const wait = Math.max(0, Math.min(ENSURE_DEBOUNCE_MS, ensureWaitStartedAt + ENSURE_MAX_WAIT_MS - now))
  ensureTimer = setTimeout(() => {
    ensureTimer = null
    ensureVisible()
  }, wait)
}

function getLoadState(key: string): MonthLoadState {
  let state = loadStates.get(key)
  if (!state) {
    state = { inflight: null, epoch: 0, failedAt: 0, keysetResets: 0 }
    loadStates.set(key, state)
  }
  return state
}

/** 所有“需要哪些数据”：预览需求优先，其次是可见窗口内各月的下标区间（按与视口的距离排序）。 */
function wantedRanges(): WantedRange[] {
  const ranges: WantedRange[] = []
  if (previewDemand) {
    ranges.push({ key: previewDemand.monthKey, from: previewDemand.from, to: previewDemand.to, priority: 0, distance: 0 })
  }
  const L = layout.value
  const win = currentWindow()
  const viewBottom = win.viewTop + viewportH()
  for (const slice of visibleSlices(win)) {
    if (slice.b <= slice.a) continue
    const gridTop = slice.sec.top + L.headerBlock
    const rowTop = gridTop + slice.r0 * L.pitch
    const rowBottom = gridTop + Math.ceil(slice.b / L.cols) * L.pitch - L.gap
    const distance = rowBottom < win.viewTop ? win.viewTop - rowBottom : rowTop > viewBottom ? rowTop - viewBottom : 0
    ranges.push({ key: slice.entry.key, from: slice.a, to: slice.b, priority: 1, distance })
  }
  return ranges.sort((x, y) => x.priority - y.priority || x.distance - y.distance)
}

/** 同步补齐可见数据（不经过防抖）。 */
function ensureVisible() {
  if (ensureTimer !== null) {
    clearTimeout(ensureTimer)
    ensureTimer = null
  }
  // 新的 summary 还没回来，或还没量到宽度：什么都不做
  if (monthsGeneration !== queryGeneration || contentWidth.value === 0) return
  const now = Date.now()
  let needRetry = false
  for (const range of wantedRanges()) {
    if (inflightLoads.size >= MAX_CONCURRENT_LOADS) break
    const entry = months.value.find((m) => m.key === range.key)
    if (!entry) continue
    const state = getLoadState(range.key)
    // 同一个月同时只有一个请求；排不上的不排队，下一次 ensureVisible 重新计算
    if (state.inflight) continue
    if (state.failedAt && now - state.failedAt < FAILURE_BACKOFF_MS) {
      needRetry = true
      continue
    }
    const run = missingRuns(entry.items, range.from, range.to)[0]
    if (!run) continue
    startMonthLoad(entry, state, run[0], run[1])
  }
  if (needRetry && retryTimer === null) {
    retryTimer = setTimeout(() => {
      retryTimer = null
      scheduleEnsureVisible()
    }, FAILURE_BACKOFF_MS)
  }
}

/**
 * 请求某月 [s, e) 这段空洞。前一格已加载就用 keyset 顺序续页（游标取自行本身，
 * 不受插入/删除影响）；否则（跳转、向上补洞）用月份内 offset。
 */
function startMonthLoad(entry: MonthEntry, state: MonthLoadState, s: number, e: number) {
  const controller = new AbortController()
  state.inflight = controller
  inflightLoads.add(controller)
  const page: TimelinePageRequest = { month: entry.key, limit: e - s > PAGE_LIMIT ? LARGE_PAGE_LIMIT : PAGE_LIMIT }
  const prev = s > 0 ? entry.items[s - 1] : undefined
  const req: PageRequestContext = { generation: queryGeneration, epoch: state.epoch, s, keyset: !!prev }
  if (prev) {
    page.afterTime = prev.exif_time ?? prev.created_at
    page.afterId = prev.id
  } else {
    page.offset = s
  }
  const key = entry.key
  listTimelineFiles(appliedQuery, page, controller.signal)
    .then((result) => applyPage(key, req, controller, result))
    .catch((error) => {
      if (axios.isCancel(error) || controller.signal.aborted || req.generation !== queryGeneration) return
      if (isInvalidTzError(error) && fallbackTimeZone()) return
      console.error('Failed to load timeline month:', key, error)
      state.failedAt = Date.now()
    })
    .finally(() => {
      if (state.inflight === controller) state.inflight = null
      inflightLoads.delete(controller)
      // 空出来的并发名额立即被用上
      ensureVisible()
    })
}

/** 把一页响应写进该月；顺带用 has_more 对账本月计数，并防止同一个月里出现重复的文件。 */
function applyPage(key: string, req: PageRequestContext, controller: AbortController, page: TimelineFilesPage) {
  if (controller.signal.aborted || req.generation !== queryGeneration) return
  const state = loadStates.get(key)
  if (!state || req.epoch !== state.epoch) return
  const entry = months.value.find((m) => m.key === key)
  if (!entry) return
  state.failedAt = 0

  const { s } = req
  const files = page.files
  const newCount = page.has_more ? Math.max(entry.count, s + files.length + 1) : s + files.length

  // 返回的 id 已经出现在别的槽位上：请求之间服务端插入/删除了行，下标错位。
  // keyset 段的内容由游标决定、不会错，只有起点 s 可能过时，所以它与任何一格重复都算冲突。
  const ids = new Set(files.map((f) => f.id))
  const scanEnd = req.keyset ? entry.items.length : Math.min(entry.items.length, newCount)
  let conflict = false
  for (let i = 0; i < scanEnd; i++) {
    if (i >= s && i < s + files.length) continue
    const f = entry.items[i]
    if (f && ids.has(f.id)) {
      conflict = true
      break
    }
  }

  // keyset 请求冲突：游标行是在服务端变化之前加载的，起点 s 按旧状态算，整段偏了插入/删除的行数。
  // 保留它会让同月几张原有照片被跳过、计数退回旧值，所以连返回段一起丢掉：整月变成空洞，
  // 计数不动，由 ensureVisible 用 offset 按服务端当前状态重拉（彼此一致，服务端不再变化就不会再冲突）。
  // 服务端持续写入时每次清空都可能再遇到冲突，所以每月每次查询最多清空 MAX_KEYSET_RESETS 次，
  // 之后退回下面“只保留本次返回段”的做法，保证每个响应都有进展。
  if (conflict && req.keyset && state.keysetResets < MAX_KEYSET_RESETS) {
    state.keysetResets++
    commitMonth(key, { ...entry, items: new Array<FileInfo | undefined>(entry.count) })
    return
  }

  // 别处的删除/新上传导致的变化
  libraryTotal.value += newCount - entry.count

  // offset 请求冲突：返回段就是服务端当前状态。不做精确对齐，整月作废只保留本次返回段，
  // 保证同一个月里没有重复的 file.id。
  let items: (FileInfo | undefined)[]
  if (conflict) {
    items = new Array<FileInfo | undefined>(newCount)
  } else {
    items = entry.items.slice(0, newCount)
    items.length = newCount
  }
  // limit 向上取整，可能覆盖到空洞后面已加载的槽位：是同一批行，直接覆盖
  for (let j = 0; j < files.length && s + j < newCount; j++) items[s + j] = files[j]
  commitMonth(key, { ...entry, count: newCount, items })
}

function abortAllLoads() {
  for (const controller of inflightLoads) controller.abort()
  inflightLoads.clear()
  loadStates.clear()
}

/** 本地删除后，该月在途响应的下标按删除前算，必须丢弃。 */
function abortAndBumpEpoch(key: string) {
  const state = getLoadState(key)
  if (state.inflight) {
    state.inflight.abort()
    inflightLoads.delete(state.inflight)
    state.inflight = null
  }
  state.epoch++
}

// --- 筛选、搜索与时区回退 ---

function currentQuery(): TimelineQuery {
  const query: TimelineQuery = { tz: timeZone }
  if (searchQuery.value) query.q = searchQuery.value
  if (selectedDevices.value.length > 0) query.devices = [...selectedDevices.value]
  if (selectedFormats.value.length > 0) query.formats = [...selectedFormats.value]
  if (selectedFocals.value.length > 0) query.focals = [...selectedFocals.value]
  if (dateRange.value && dateRange.value[0] && dateRange.value[1]) {
    query.dateFrom = dateRange.value[0]
    query.dateTo = dateRange.value[1]
  }
  if (dirPath.value) {
    query.path = dirPath.value
    query.recursive = dirRecursive.value
  }
  if (captureDay.value) query.captureDate = captureDay.value
  return query
}

// --- URL ↔ 筛选状态 ---

interface TimelineFilterState {
  q: string
  devices: string[]
  formats: string[]
  focals: string[]
  dateFrom: string // 与 dateTo 同时为空或同时有值
  dateTo: string
  day: string
  path: string
  recursive: boolean // path 为空时恒为 true
}

const FOCAL_KEYS = new Set(FOCAL_BUCKETS.map((b) => b.key))
const SEARCH_MAX_LENGTH = 200
const PATH_MAX_LENGTH = 1024

/** URL 里某个键的全部非空字符串值（重复键 → 多个值）。 */
function queryValues(value: LocationQuery[string] | undefined): string[] {
  const list = Array.isArray(value) ? value : [value]
  return list.filter((v): v is string => typeof v === 'string' && v !== '')
}

/** 重复键取第一个值，与数据统计页 scopeFromRoute 一致。 */
function firstQueryValue(value: LocationQuery[string] | undefined): string {
  const v = Array.isArray(value) ? value[0] : value
  return typeof v === 'string' ? v : ''
}

/** 合法的公历日期 YYYY-MM-DD（2026-13-45 之类的会被服务端 422，这里先丢掉）。 */
function isValidYmd(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return false
  const d = new Date(`${s}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s
}

/** 与服务端 normalize_virtual_dir_path 同规则；不合法时返回 ''（全部），不让页面落到错误态。 */
function normalizeDirPath(raw: string): string {
  const path = raw.replace(/^\/+|\/+$/g, '')
  if (path === '') return ''
  if (path.length > PATH_MAX_LENGTH || /[\\\0]/.test(path)) return ''
  if (path.split('/').some((seg) => seg === '' || seg === '.' || seg === '..')) return ''
  return path
}

/**
 * URL → 筛选状态的唯一归一化入口（宽松处理，非法值直接丢弃）：
 * - 日期不是合法 YYYY-MM-DD、from / to 只给了一个、from 晚于 to：日期范围作废；
 * - focal 只保留已知焦段；重复值去重；
 * - path 去掉首尾 '/'，不合法时当作全部；recursive 除 '0' 以外一律为 true，path 为空时恒为 true。
 */
function timelineFiltersFromRoute(query: LocationQuery): TimelineFilterState {
  const unique = (values: string[]) => [...new Set(values)]
  let dateFrom = firstQueryValue(query.from)
  let dateTo = firstQueryValue(query.to)
  if (!isValidYmd(dateFrom) || !isValidYmd(dateTo) || dateFrom > dateTo) {
    dateFrom = ''
    dateTo = ''
  }
  const day = firstQueryValue(query.day)
  const path = normalizeDirPath(firstQueryValue(query.path))
  return {
    q: firstQueryValue(query.q).trim().slice(0, SEARCH_MAX_LENGTH),
    devices: unique(queryValues(query.device)),
    formats: unique(queryValues(query.format)),
    focals: unique(queryValues(query.focal).filter((k) => FOCAL_KEYS.has(k))),
    dateFrom,
    dateTo,
    day: isValidYmd(day) ? day : '',
    path,
    recursive: path === '' || firstQueryValue(query.recursive) !== '0',
  }
}

/** 筛选状态 → URL：只写非默认值（recursive=0 只在选了目录且不含子目录时出现）。 */
function timelineQueryToRoute(state: TimelineFilterState): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.q) query.q = state.q
  if (state.devices.length > 0) query.device = [...state.devices]
  if (state.formats.length > 0) query.format = [...state.formats]
  if (state.focals.length > 0) query.focal = [...state.focals]
  if (state.dateFrom && state.dateTo) {
    query.from = state.dateFrom
    query.to = state.dateTo
  }
  if (state.day) query.day = state.day
  if (state.path) {
    query.path = state.path
    if (!state.recursive) query.recursive = '0'
  }
  return query
}

/** 当前控件所代表的筛选状态（搜索词取已生效的 searchQuery，不是输入框里正在打的字）。 */
function currentFilterState(): TimelineFilterState {
  const range = dateRange.value
  const hasRange = !!(range && range[0] && range[1])
  return {
    q: searchQuery.value,
    devices: [...selectedDevices.value],
    formats: [...selectedFormats.value],
    focals: [...selectedFocals.value],
    dateFrom: hasRange ? range![0] : '',
    dateTo: hasRange ? range![1] : '',
    day: captureDay.value,
    path: dirPath.value,
    recursive: dirPath.value === '' || dirRecursive.value,
  }
}

function applyFilterState(state: TimelineFilterState) {
  searchInput.value = state.q
  searchQuery.value = state.q
  selectedDevices.value = [...state.devices]
  selectedFormats.value = [...state.formats]
  selectedFocals.value = [...state.focals]
  dateRange.value = state.dateFrom && state.dateTo ? [state.dateFrom, state.dateTo] : null
  captureDay.value = state.day
  dirPath.value = state.path
  dirRecursive.value = state.recursive
}

const filterKey = (state: TimelineFilterState) => JSON.stringify(state)

/** 把当前筛选写回 URL。replace 而不是 push：切换筛选不算导航，不堆历史。 */
function syncRouteQuery() {
  router
    .replace({ name: 'Timeline', query: timelineQueryToRoute(currentFilterState()) })
    .catch((error) => console.error('Failed to update timeline URL:', error))
}

// 深链接以外的 URL 变化（浏览器前进 / 后退、从别的页面带参数跳过来）：
// 归一化后与当前状态相同（包括控件变化后自己写回的 URL）就什么都不做，不会重复请求。
watch(
  () => route.query,
  (query) => {
    // 离开页面时 query 也会变化，不能因此多发一次请求
    if (route.name !== 'Timeline') return
    const next = timelineFiltersFromRoute(query)
    if (filterKey(next) === filterKey(currentFilterState())) return
    applyFilterState(next)
    void reloadTimeline()
  }
)

/** 'YYYY-MM-DD' → 'YYYY年M月D日' */
function formatCaptureDay(ymd: string): string {
  const [y, m, d] = ymd.split('-').map(Number)
  return `${y}年${m}月${d}日`
}

/** 按当前筛选重新请求 summary，装入新的月份骨架。旧内容保留到新结果回来，不闪加载态。 */
async function reloadTimeline() {
  const generation = ++queryGeneration
  summaryController?.abort()
  abortAllLoads()
  if (!loaded.value) {
    loading.value = true
    loadFailed.value = false
  }
  const query = currentQuery()
  const controller = new AbortController()
  summaryController = controller
  try {
    const summary = await getTimelineSummary(query, controller.signal)
    if (generation !== queryGeneration) return
    appliedQuery = query
    months.value = summary.months.map((m) => ({
      key: monthKeyOf(m.year, m.month),
      year: m.year,
      month: m.month,
      count: m.count,
      items: new Array<FileInfo | undefined>(m.count),
    }))
    monthsGeneration = generation
    libraryTotal.value = summary.total_count
    pendingScrollAdjust = 0
    if (pendingJump) {
      clearTimeout(pendingJump.timer)
      pendingJump = null
    }
    const body = pageBodyRef.value
    if (body) body.scrollTop = 0
    scrollTop.value = 0
    activeYear.value = null
    activeMonth.value = null
    // Auto-expand the first year as soon as the navigation is ready.
    expandedYears.value = new Set(months.value.length > 0 ? [months.value[0].year] : [])
    previewVisible.value = false
    previewDemand = null
    loading.value = false
    loaded.value = true
    loadFailed.value = false
    ensureVisible()
  } catch (error) {
    // 被取消的 summary 不改 loading，由更新的那次请求负责收尾
    if (generation !== queryGeneration || axios.isCancel(error)) return
    if (isInvalidTzError(error) && fallbackTimeZone()) return
    console.error('Failed to load timeline summary:', error)
    ElMessage.error('加载时间线失败')
    loading.value = false
    if (!loaded.value) loadFailed.value = true
  } finally {
    if (summaryController === controller) summaryController = null
  }
}

async function loadFacets() {
  const seq = ++facetsSeq
  facetsController?.abort()
  const controller = new AbortController()
  facetsController = controller
  try {
    const result = await getTimelineFacets(timeZone, controller.signal)
    // 挂载时一次、每次删除后一次，可能并发；旧响应不能覆盖新的
    if (seq !== facetsSeq) return
    facets.value = result
  } catch (error) {
    if (seq !== facetsSeq || axios.isCancel(error)) return
    if (isInvalidTzError(error) && fallbackTimeZone()) return
    // 选项为空、日期全部淡化，页面仍可用
    console.error('Failed to load timeline facets:', error)
  } finally {
    if (facetsController === controller) facetsController = null
  }
}

/** 服务端不认识浏览器时区：去掉 tz 重新开始（只做一次）。返回是否已处理。 */
function fallbackTimeZone(): boolean {
  if (tzFallbackDone || timeZone === undefined) return false
  console.warn('Server does not recognise browser time zone, falling back to server default:', timeZone)
  timeZone = undefined
  tzFallbackDone = true
  void loadFacets()
  void reloadTimeline()
  return true
}

function retryLoad() {
  if (!facets.value) void loadFacets()
  void reloadTimeline()
}

// --- Methods ---
function toggleYear(year: number) {
  if (expandedYears.value.has(year)) {
    expandedYears.value.delete(year)
  } else {
    expandedYears.value.add(year)
  }
  // Trigger reactivity
  expandedYears.value = new Set(expandedYears.value)
}

/** 所有控件变化的唯一出口：先写回 URL，再按新筛选请求一次（URL 回写触发的 watch 不会再请求）。 */
function handleFilterChange() {
  syncRouteQuery()
  void reloadTimeline()
}

function onDirPathChange(path: string) {
  if (path === dirPath.value) return
  dirPath.value = path
  // 回到根目录时“包含子目录”恒为开；之后再选目录从默认（包含）开始
  if (path === '') dirRecursive.value = true
  handleFilterChange()
}

function onDirRecursiveChange(value: string | number | boolean) {
  dirRecursive.value = value === true
  handleFilterChange()
}

function clearCaptureDay() {
  captureDay.value = ''
  handleFilterChange()
}

// 搜索只在按回车时执行：每次生效服务端执行一次 summary，再加最新月份的 files 第 1 页。
// 打字、手动删空输入框都不会触发；点输入框的清除按钮清空并立即恢复全部结果。

/** 把搜索框内容（去掉首尾空白）应用为搜索词；与当前搜索词相同时不重新请求。空串即取消搜索。 */
function applySearchNow() {
  const q = searchInput.value.trim()
  if (searchQuery.value !== q) {
    searchQuery.value = q
    handleFilterChange()
  }
}

function handleSearchEnter(event: Event | KeyboardEvent) {
  // 输入法组字时按回车是确认候选词，不是搜索（Safari 在 compositionend 之后才发 keydown，只能看 keyCode 229）
  if (event instanceof KeyboardEvent && (event.isComposing || event.keyCode === 229)) return
  applySearchNow()
}

function resetFilters() {
  searchInput.value = ''
  searchQuery.value = ''
  selectedDevices.value = []
  selectedFormats.value = []
  selectedFocals.value = []
  dateRange.value = null
  captureDay.value = ''
  dirPath.value = ''
  dirRecursive.value = true
  handleFilterChange()
}

// --- 大图预览（在同一个月内翻页，跨分页边界时按需加载） ---
function openPreview(entry: MonthEntry, index: number) {
  previewMonthKey.value = entry.key
  previewIndex.value = index
  previewVisible.value = true
}

function handlePreviewIndexChange(index: number) {
  const entry = months.value.find((m) => m.key === previewMonthKey.value)
  if (!entry) return
  previewDemand = { monthKey: entry.key, from: Math.max(0, index - 50), to: Math.min(entry.count, index + 51) }
  ensureVisible()
}

watch(previewVisible, (visible) => {
  if (!visible) {
    previewDemand = null
    ensureVisible()
  }
})

function formatShortDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '-'
  const date = new Date(normalizeTimestamp(dateStr))
  if (Number.isNaN(date.getTime())) return '-'
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

function onTileContextMenu(event: MouseEvent, tile: RenderedTile, monthKey: string) {
  if (tile.file) showContextMenu(event, tile.file, monthKey)
}

function showContextMenu(event: MouseEvent, file: FileInfo, monthKey: string) {
  event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    file,
    monthKey,
    generation: queryGeneration,
  }
}

function closeContextMenu() {
  contextMenu.value.visible = false
  contextMenu.value.file = null
  contextMenu.value.monthKey = null
}

function handleContextDownload() {
  if (contextMenu.value.file) {
    downloadFile(contextMenu.value.file.id, contextMenu.value.file.file_name)
  }
  closeContextMenu()
}

async function handleContextDelete() {
  const file = contextMenu.value.file
  const key = contextMenu.value.monthKey
  const openedGeneration = contextMenu.value.generation
  closeContextMenu()
  if (!file || !key) return
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
    removeDeletedFile(file, key, openedGeneration)
    trashStore.refresh()
    // 选项与“有照片的日期”在后台刷新
    void loadFacets()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('移入回收站失败')
    }
  }
}

/**
 * 删除成功后从本地数据里移除这张照片。确认框打开期间该月的在途请求可能已经改写过
 * 槽位，甚至重新查询过，所以按 id 找真正装着它的槽位；找不到就整月作废重拉，
 * 绝不按下标猜（splice(-1, 1) 会删掉该月最后一张另一张照片）。
 */
function removeDeletedFile(file: FileInfo, key: string, openedGeneration: number) {
  // 先找真正装着这张照片的月份（优先传入的 key），再退回到只按 key 找
  const holder =
    months.value.find((m) => m.key === key && m.items.some((f) => f?.id === file.id)) ??
    months.value.find((m) => m.items.some((f) => f?.id === file.id))
  // 没找到槽位时，只有“右键菜单打开后没有重新查询过”才能确定它属于 key 这个月
  const target =
    holder ?? (openedGeneration === queryGeneration ? months.value.find((m) => m.key === key) : undefined)
  if (!target) {
    // 确认框打开期间重新筛选过，且新结果里没有已加载的这张照片：
    // 不知道它是否属于某个月，月份计数交给后续分页的对账，只调整全库计数
    libraryTotal.value--
  } else {
    abortAndBumpEpoch(target.key)
    const idx = target.items.findIndex((f) => f?.id === file.id)
    let items: (FileInfo | undefined)[]
    if (idx >= 0) {
      items = target.items.slice()
      items.splice(idx, 1)
    } else {
      // 照片已不在任何已加载槽位上，不知道它原来在哪：整月作废重拉，长度减 1
      items = new Array<FileInfo | undefined>(target.count - 1)
    }
    commitMonth(target.key, { ...target, count: target.count - 1, items }) // 为 0 则移除该月
    libraryTotal.value--
  }
  ensureVisible()
}

// --- Lifecycle ---
let resizeObserver: ResizeObserver | null = null

function handleUserTakeover() {
  if (pendingJump) clearPendingJump()
}

onMounted(() => {
  configStore.ensureLoaded()
  loading.value = true
  const body = pageBodyRef.value!
  const style = getComputedStyle(body)
  // 两者定义在 :root，会继承下来
  tileMin.value = Number.parseFloat(style.getPropertyValue('--pv-tile-min')) || 180
  gridGap.value = Number.parseFloat(style.getPropertyValue('--pv-gap')) || 12
  bodyPaddingBottom.value = Number.parseFloat(style.paddingBottom) || 0
  measureHeaderBlock()

  // 宽度和视口高度都取自常驻的 .pv-page-body（它不在 v-if 里）
  resizeObserver = new ResizeObserver(([entry]) => {
    const oldCols = layout.value.cols
    const anchor = captureAnchor()
    // 视口高度不超过窗口高度：滚动容器没有被限高时（例如测试夹具），底部垫高不会反过来把它越撑越高
    viewportHeight.value = Math.min(body.clientHeight, window.innerHeight)
    // .photos-content 是 pageBody 的块级子元素、没有外边距，宽度 = pageBody 内容盒宽度（不含内边距和滚动条）。
    // contentRect.width 是小数宽度，与 CSS grid 的轨道宽度一致。
    contentWidth.value = entry.contentRect.width
    measureHeaderBlock()
    if (anchor && layout.value.cols !== oldCols) {
      void nextTick(() => restoreAnchor(anchor))
    }
    ensureVisible()
  })
  resizeObserver.observe(body)
  body.addEventListener('scroll', handleScroll, { passive: true })
  for (const name of JUMP_CANCEL_EVENTS) body.addEventListener(name, handleUserTakeover, { passive: true })

  // 开发构建下把布局挂到 window 上供 Playwright 读取；生产构建里整段被去掉
  if (import.meta.env.DEV) {
    Object.defineProperty(window, '__pvTimelineLayout', { get: () => layout.value, configurable: true })
  }

  // 第一次请求之前先从 URL 恢复筛选（深链接、刷新页面）
  applyFilterState(timelineFiltersFromRoute(route.query))
  void loadFacets()
  void reloadTimeline()
})

onBeforeUnmount(() => {
  queryGeneration++
  facetsSeq++
  summaryController?.abort()
  facetsController?.abort()
  abortAllLoads()
  if (ensureTimer !== null) clearTimeout(ensureTimer)
  if (retryTimer !== null) clearTimeout(retryTimer)
  if (pendingJump) clearTimeout(pendingJump.timer)
  pendingJump = null
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  resizeObserver?.disconnect()
  const body = pageBodyRef.value
  if (body) {
    body.removeEventListener('scroll', handleScroll)
    for (const name of JUMP_CANCEL_EVENTS) body.removeEventListener(name, handleUserTakeover)
  }
  if (import.meta.env.DEV) {
    delete (window as unknown as Record<string, unknown>).__pvTimelineLayout
  }
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
  background: var(--pv-brand-container);
  color: var(--pv-brand-ink);
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
  background: var(--pv-brand-container);
  color: var(--pv-brand-ink);
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

/* 筛选栏：标题右侧两行，每行靠右对齐、放不下时自行换行。第 1 行是搜索 / 目录 /
   包含子目录 / 清除筛选，第 2 行是设备 / 格式 / 焦段 / 日期范围 / 拍摄日期标签。
   宽屏下两行各自一行排开、标题在左侧；窄窗口整块换到标题下面（见下方 .pv-page-header 降级）。 */
.tl-filters {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 100%;
}

.tl-filter-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}

/* 标题栏卡片背景跟随 Element 的主题变量（浅色下与 .pv-panel 的 #fff 相同），
   html.dark 下与暗色控件一致。只作用于时间线页，不改共享的 PageHeader / layout.css。 */
.pv-page-head :deep(.pv-page-header) {
  background-color: var(--el-bg-color-overlay);
}

/* 目录选择器：固定宽度，窄到放不下时不超过所在行 */
.dir-filter {
  flex: 0 0 auto;
  width: 220px;
  max-width: 100%;
}

.dir-filter :deep(.directory-tree-select) {
  width: 100%;
}

.dir-filter.is-active :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
  background-color: var(--el-fill-color-blank);
}

.recursive-switch {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
}

/* 拍摄日期标签：与控件等高；品牌深红字配浅红底（对比度 ≥ 4.5:1，同“已筛选”徽标） */
.capture-day-tag {
  max-width: 100%;
  height: 32px;
  font-size: 14px;
  color: var(--pv-brand-ink);
  background-color: var(--pv-brand-container);
  border-color: var(--el-color-primary-light-5);
}

.capture-day-tag :deep(.el-tag__close) {
  color: var(--pv-brand-ink);
}

.capture-day-tag :deep(.el-tag__close:hover) {
  color: #fff;
  background-color: var(--el-color-primary);
}

.filter-select {
  width: 140px;
}

/* 文件名搜索框：固定宽度，避免 el-input 在 flex 容器里被拉伸。 */
.filename-search {
  flex: 0 0 auto;
  width: 200px;
}

/* 有搜索词时和其它筛选器一样加品牌红描边 */
.filename-search.is-active :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
}

/* Brand-red selected tags inside the filter dropdowns */
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
  background-color: var(--el-fill-color-blank);
}

/* 日期区间控件：Element 的 .el-input__wrapper 带 flex-grow:1，在标题栏的
   flex 容器里会被拉满整行，这里固定宽度，宽屏下第 2 行的各筛选器排成一行。 */
.date-filter {
  flex: 0 0 auto;
  width: 280px;
}

.date-filter :deep(.el-date-editor) {
  width: 100%;
  /* Element 的 .el-range-editor 带 padding: 0 10px 且是 content-box，
     width:100% 会再加 20px，右端超出白色标题栏。 */
  box-sizing: border-box;
}

/* 窄窗口降级：标题 + 最宽的单个控件（280px 日期框）放不下时，整块两行筛选栏换到
   标题下一行，而不是溢出卡片。flex-basis 0 让筛选区只在真放不下时才换行，
   宽屏布局保持不变。只作用于时间线页，不改共享的 PageHeader。 */
.pv-page-head :deep(.pv-page-header) {
  flex-wrap: wrap;
}

/* 两行筛选栏的第 1 行（搜索 200 + 目录 220 + 开关约 120 + 间距）约 556px：标题右侧
   放不下这一行时整块换到标题下面，而不是挤成窄窄一列、控件错落成五六行。 */
.pv-page-head :deep(.pv-page-header__extra) {
  flex: 1 1 560px;
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
  color: var(--pv-brand-ink);
  background: var(--pv-brand-container);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 12px;
}

/* Photo groups：窗口化渲染，区块和网格都绝对定位，位置由 timelineLayout 算出；
   .photos-content 的高度（含底部垫高）由内联样式给出。 */
.photos-content {
  position: relative;
}

.photo-group {
  position: absolute;
  left: 0;
  right: 0;
}

.photo-group .pv-photo-grid {
  position: absolute;
  left: 0;
  right: 0;
}

/* 未加载位置的占位瓦片：浅灰底，没有悬停描边 */
.pv-tile.is-placeholder {
  cursor: default;
  background: var(--el-fill-color-light);
}

.pv-tile.is-placeholder:hover {
  border-color: var(--pv-divider-color);
  box-shadow: none;
}

/* 测量分组标题高度的探针：.pv-page-body 是 position: relative，左右各缩进一个
   gutter 后与内容区等宽 */
.header-probe {
  position: absolute;
  visibility: hidden;
  pointer-events: none;
  top: 0;
  left: var(--pv-page-gutter);
  right: var(--pv-page-gutter);
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
