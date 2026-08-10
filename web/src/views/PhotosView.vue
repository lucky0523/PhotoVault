<template>
  <div class="photos-view">
    <el-container class="photos-container">
      <!-- Left sidebar: Directory tree -->
      <el-aside width="250px" class="directory-sidebar">
        <div class="sidebar-header">
          <el-icon><FolderOpened /></el-icon>
          <span>目录</span>
        </div>
        <el-tree
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          node-key="path"
          lazy
          :load="loadTreeNode"
          :highlight-current="true"
          @node-click="handleNodeClick"
          class="directory-tree"
        >
          <template #default="{ node, data }">
            <span class="tree-node-label">
              <el-icon v-if="node.loading" class="is-loading"><Loading /></el-icon>
              <el-icon v-else-if="data.isDevice"><Monitor /></el-icon>
              <el-icon v-else><Folder /></el-icon>
              <span>{{ node.label }}</span>
              <span v-if="data.file_count" class="tree-node-count">{{ data.file_count }}</span>
              <!-- file_count here holds the active (backed_up) count set in loadTreeNode -->
            </span>
          </template>
        </el-tree>
      </el-aside>

      <!-- Right content area -->
      <el-main class="content-area">
        <div class="pv-page-head">
          <!-- Toolbar -->
          <PageHeader title="图片浏览" :icon="Picture">
            <template #subtitle>
              <!-- Breadcrumb：当前路径作为标题的副信息 -->
              <el-breadcrumb separator="/" class="path-breadcrumb">
                <el-breadcrumb-item @click="navigateTo('')">
                  <el-icon><HomeFilled /></el-icon>
                </el-breadcrumb-item>
                <el-breadcrumb-item
                  v-for="(segment, index) in pathSegments"
                  :key="index"
                  @click="navigateTo(pathSegments.slice(0, index + 1).join('/'))"
                >
                  {{ segment }}
                </el-breadcrumb-item>
              </el-breadcrumb>
            </template>

            <template #extra>
              <!-- Batch actions: shown whenever there's a selection -->
              <template v-if="selectedIds.size > 0">
                <span class="pv-muted">已选择 {{ selectedIds.size }} 项</span>
                <el-button @click="selectAllFiles">全选</el-button>
                <el-button @click="clearSelection">取消选择</el-button>
                <el-button type="primary" @click="handleBatchDownload">
                  <el-icon><Download /></el-icon>
                  下载
                </el-button>
                <el-button type="danger" @click="handleBatchDelete">
                  <el-icon><Delete /></el-icon>
                  移入回收站
                </el-button>
              </template>

              <!-- Sort dropdown -->
              <el-select v-model="sortBy" style="width: 120px" @change="loadContent">
                <el-option label="名称" value="name" />
                <el-option label="大小" value="size" />
                <el-option label="时间" value="time" />
              </el-select>

              <!-- View toggle -->
              <el-button-group>
                <el-button
                  :type="viewMode === 'grid' ? 'primary' : 'default'"
                  @click="setViewMode('grid')"
                >
                  <el-icon><Grid /></el-icon>
                </el-button>
                <el-button
                  :type="viewMode === 'list' ? 'primary' : 'default'"
                  @click="setViewMode('list')"
                >
                  <el-icon><List /></el-icon>
                </el-button>
              </el-button-group>
            </template>
          </PageHeader>
        </div>

        <div class="pv-page-body">
          <!-- Loading state -->
          <div v-if="contentLoading" class="pv-loading">
            <el-icon class="is-loading" :size="32"><Loading /></el-icon>
            <span>加载中...</span>
          </div>

          <!-- Empty state -->
          <el-empty
            v-else-if="directories.length === 0 && files.length === 0"
            description="当前目录为空"
          />

          <!-- Content -->
          <template v-else>
            <!-- Directories section -->
            <div v-if="directories.length > 0" class="directories-section">
              <div class="pv-section-title">文件夹</div>
              <!-- Grid view -->
              <div v-if="viewMode === 'grid'" class="directories-grid">
                <div
                  v-for="dir in directories"
                  :key="dir.path"
                  class="directory-card"
                  @click="navigateTo(dir.path)"
                  @contextmenu="showContextMenu($event, 'directory', undefined, dir)"
                >
                  <el-icon :size="32" color="var(--el-color-warning)"><Folder /></el-icon>
                  <div class="directory-info">
                    <span class="directory-name">{{ dir.name }}</span>
                    <span class="directory-count">{{ dir.backed_up_count }} 个文件</span>
                  </div>
                </div>
              </div>
              <!-- List view -->
              <el-table
                v-else
                :data="directories"
                style="width: 100%"
                @row-click="(row: DirectoryInfo) => navigateTo(row.path)"
                @row-contextmenu="(row: DirectoryInfo, event: MouseEvent) => showContextMenu(event, 'directory', undefined, row)"
              >
                <el-table-column width="50">
                  <template #default>
                    <el-icon :size="20" color="var(--el-color-warning)"><Folder /></el-icon>
                  </template>
                </el-table-column>
                <el-table-column prop="name" label="名称" min-width="200" />
                <el-table-column label="文件数" width="100">
                  <template #default="{ row }">
                    {{ row.backed_up_count }}
                  </template>
                </el-table-column>
                <el-table-column label="大小" width="120">
                  <template #default="{ row }">
                    {{ formatFileSize(row.size) }}
                  </template>
                </el-table-column>
                <el-table-column label="最后更新" width="160">
                  <template #default="{ row }">
                    {{ formatDate(row.latest_file_time) }}
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="120" fixed="right">
                  <template #default="{ row }">
                    <div class="row-actions">
                      <el-button type="danger" link size="small" @click.stop="handleDeleteDirectory(row)">
                        移入回收站
                      </el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <!-- Files section -->
            <div v-if="files.length > 0" class="files-section">
              <div class="pv-section-title">文件</div>

              <!-- Grid view -->
              <div v-show="viewMode === 'grid'" class="pv-photo-grid">
                <div
                  v-for="(file, index) in files"
                  :key="file.id"
                  class="pv-tile"
                  :class="{ 'is-selected': selectedIds.has(file.id) }"
                  @click="handleCardClick(file, index)"
                  @contextmenu="showContextMenu($event, 'file', file)"
                >
                  <div
                    class="pv-tile__check"
                    :class="{ 'is-checked': selectedIds.has(file.id) }"
                    @click.stop="toggleSelect(file.id)"
                  >
                    <el-icon v-if="selectedIds.has(file.id)"><Check /></el-icon>
                  </div>
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
                    <span class="pv-tile__meta">{{ formatFileSize(file.file_size) }}</span>
                  </div>
                </div>
              </div>

              <!-- List view -->
              <div v-show="viewMode === 'list'" ref="listContainerRef" class="list-view-container">
                <el-table
                  ref="fileTableRef"
                  :data="files"
                  :height="tableHeight"
                  style="width: 100%"
                  row-key="id"
                  @row-click="(row: FileInfo, column: any) => handleRowClick(row, column)"
                  @row-contextmenu="(row: FileInfo, event: MouseEvent) => showContextMenu(event, 'file', row)"
                  @selection-change="handleTableSelectionChange"
                >
                <el-table-column type="selection" width="50" :selectable="() => true" />
                <el-table-column width="60">
                  <template #default="{ row }">
                    <div class="list-thumbnail-wrap">
                      <img
                        :src="getThumbnailUrl(row.id, 'small')"
                        class="list-thumbnail"
                        :alt="row.file_name"
                        @error="handleThumbnailError"
                      />
                      <el-icon v-if="isVideo(row)" class="list-video-badge" :size="16"><VideoPlay /></el-icon>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="file_name" label="文件名" min-width="200" />
                <el-table-column label="大小" width="100">
                  <template #default="{ row }">
                    {{ formatFileSize(row.file_size) }}
                  </template>
                </el-table-column>
                <el-table-column label="拍摄时间" width="160">
                  <template #default="{ row }">
                    {{ row.exif_time ? formatDate(row.exif_time) : '—' }}
                  </template>
                </el-table-column>
                <el-table-column label="上传时间" width="160">
                  <template #default="{ row }">
                    {{ formatDate(row.created_at) }}
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="80">
                  <template #default="{ row }">
                    {{ getFileType(row.file_name) }}
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <div class="row-actions">
                      <el-button
                        type="primary"
                        link
                        size="small"
                        @click.stop="handleDownloadClick(row)"
                      >
                        下载
                      </el-button>
                      <el-button
                        type="danger"
                        link
                        size="small"
                        @click.stop="handleDeleteFile(row)"
                      >
                        移入回收站
                      </el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              </div>
            </div>

            <!-- Pagination -->
            <div v-if="totalFiles > pageSize" class="pagination-container">
              <el-pagination
                v-model:current-page="currentPage"
                :page-size="pageSize"
                :total="totalFiles"
                layout="prev, pager, next, total"
                @current-change="handlePageChange"
              />
            </div>
          </template>
        </div>
      </el-main>
    </el-container>

    <!-- Image Preview Lightbox -->
    <ImagePreview
      v-model:visible="previewVisible"
      :files="files"
      :initial-index="previewIndex"
    />

    <!-- Context Menu -->
    <teleport to="body">
      <div
        v-if="contextMenu.visible"
        class="pv-context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <template v-if="contextMenu.type === 'file'">
          <div class="pv-context-menu__item" @click="handleContextDownload">
            <el-icon><Download /></el-icon>
            下载
          </div>
          <div class="pv-context-menu__item is-danger" @click="handleContextDelete">
            <el-icon><Delete /></el-icon>
            移入回收站
          </div>
        </template>
        <template v-else-if="contextMenu.type === 'directory'">
          <div class="pv-context-menu__item is-danger" @click="handleContextDelete">
            <el-icon><Delete /></el-icon>
            移入回收站
          </div>
        </template>
      </div>
      <div v-if="contextMenu.visible" class="pv-context-menu__overlay" @click="closeContextMenu" />
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import {
  FolderOpened,
  Folder,
  Monitor,
  HomeFilled,
  Grid,
  List,
  Loading,
  Delete,
  Download,
  Check,
  Picture,
  VideoPlay,
} from '@element-plus/icons-vue'
import type { ElTree, ElTable } from 'element-plus'
import {
  browseFiles,
  listFiles,
  getThumbnailUrl,
  downloadFile,
  deleteFile,
  deleteDirectory,
  formatFileSize,
  formatDate,
} from '@/api/files'
import type { DirectoryInfo, FileInfo } from '@/api/files'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImagePreview from '@/components/ImagePreview.vue'
import LivePhotoIcon from '@/components/LivePhotoIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import { isVideo, isMotionPhoto, handleThumbnailError } from '@/utils/media'
import { useTrashStore } from '@/stores/trash'
import { useConfigStore } from '@/stores/config'

const trashStore = useTrashStore()
const configStore = useConfigStore()

// Tree
interface TreeNode {
  label: string
  path: string
  isLeaf: boolean
  isDevice?: boolean
  file_count?: number
}

const treeRef = ref<InstanceType<typeof ElTree>>()
const treeData = ref<TreeNode[]>([])
const treeProps = {
  label: 'label',
  children: 'children',
  isLeaf: 'isLeaf',
}

// Content state
const currentPath = ref('')
const directories = ref<DirectoryInfo[]>([])
const files = ref<FileInfo[]>([])
const totalFiles = ref(0)
const currentPage = ref(1)
const pageSize = ref(50)
const sortBy = ref('name')
const viewMode = ref<'grid' | 'list'>('grid')

// List-view table sizing: the el-table needs an explicit pixel height, so we
// measure the container's distance from the viewport top and let the table fill
// the remaining space instead of using a fixed height.
const listContainerRef = ref<HTMLElement | null>(null)
const tableHeight = ref(500)

function updateTableHeight() {
  nextTick(() => {
    const el = listContainerRef.value
    if (!el) return
    const top = el.getBoundingClientRect().top
    // In grid mode the container is display:none (top === 0); skip then.
    if (top <= 0) return
    // Reserve space for bottom padding, plus the pager when it's shown.
    const reserved = totalFiles.value > pageSize.value ? 84 : 24
    tableHeight.value = Math.max(window.innerHeight - top - reserved, 200)
  })
}

function setViewMode(mode: 'grid' | 'list') {
  viewMode.value = mode
  if (mode === 'list') updateTableHeight()
}

const contentLoading = ref(false)

// Preview state
const previewVisible = ref(false)
const previewIndex = ref(0)

// Multi-select state — checkboxes are always visible; selectedIds drives the batch toolbar
const fileTableRef = ref<InstanceType<typeof ElTable>>()
const selectedIds = ref<Set<number>>(new Set())

function clearSelection() {
  selectedIds.value = new Set()
  fileTableRef.value?.clearSelection()
}

function selectAllFiles() {
  selectedIds.value = new Set(files.value.map((f) => f.id))
  if (viewMode.value === 'list') {
    nextTick(() => {
      files.value.forEach((f) => fileTableRef.value?.toggleRowSelection(f, true))
    })
  }
}

function toggleSelect(fileId: number) {
  if (selectedIds.value.has(fileId)) {
    selectedIds.value.delete(fileId)
  } else {
    selectedIds.value.add(fileId)
  }
  // Keep the value reassigned so Vue's reactivity picks up Set mutations
  selectedIds.value = new Set(selectedIds.value)
}

// Grid card click: open preview (checkbox has its own click handler and stops propagation)
function handleCardClick(_file: FileInfo, index: number) {
  openPreview(index)
}

// Table row selection changed via checkbox column (list view)
function handleTableSelectionChange(rows: FileInfo[]) {
  selectedIds.value = new Set(rows.map((r) => r.id))
}

async function handleBatchDownload() {
  const targets = files.value.filter((f) => selectedIds.value.has(f.id))
  if (targets.length === 0) return
  for (const file of targets) {
    downloadFile(file.id, file.file_name)
    // Small delay so the browser doesn't block multiple simultaneous downloads
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  ElMessage.success(`已开始下载 ${targets.length} 个文件`)
}

async function handleBatchDelete() {
  const targets = files.value.filter((f) => selectedIds.value.has(f.id))
  if (targets.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定要将选中的 ${targets.length} 个文件移入回收站吗？\n${configStore.trashRetentionDays} 天后自动彻底删除，可在回收站中恢复。`,
      '移入回收站',
      {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
    const results = await Promise.allSettled(targets.map((f) => deleteFile(f.id)))
    const failed = results.filter((r) => r.status === 'rejected').length
    if (failed > 0) {
      ElMessage.warning(`已移入回收站 ${targets.length - failed} 个，${failed} 个失败`)
    } else {
      ElMessage.success(`已将 ${targets.length} 个文件移入回收站`)
    }
    clearSelection()
    loadContent()
    trashStore.refresh()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

// Context menu state
interface ContextMenuState {
  visible: boolean
  x: number
  y: number
  type: 'file' | 'directory' | null
  file: FileInfo | null
  directory: DirectoryInfo | null
}
const contextMenu = ref<ContextMenuState>({
  visible: false,
  x: 0,
  y: 0,
  type: null,
  file: null,
  directory: null,
})

// Computed
const pathSegments = computed(() => {
  if (!currentPath.value) return []
  return currentPath.value.split('/').filter(Boolean)
})

// Tree loading
async function loadTreeNode(
  node: { level: number; data: TreeNode },
  resolve: (data: TreeNode[]) => void
) {
  if (node.level === 0) {
    // Root level: load devices (top-level directories)
    try {
      const response = await browseFiles('', 1, 100)
      const nodes: TreeNode[] = response.directories
        .filter((dir) => dir.name !== '.trash')
        .map((dir) => ({
          label: dir.name,
          path: dir.path,
          isLeaf: false,
          isDevice: true,
          file_count: dir.backed_up_count,
        }))
      treeData.value = nodes
      resolve(nodes)
    } catch {
      resolve([])
    }
  } else {
    // Load children of a node
    try {
      const response = await browseFiles(node.data.path, 1, 100)
      const visibleDirs = response.directories.filter((dir) => dir.name !== '.trash')
      const nodes: TreeNode[] = visibleDirs.map((dir) => ({
        label: dir.name,
        path: dir.path,
        isLeaf: dir.backed_up_count === 0 && visibleDirs.length === 0,
        file_count: dir.backed_up_count,
      }))
      // If no subdirectories, mark as leaf
      if (nodes.length === 0) {
        resolve([])
      } else {
        resolve(nodes)
      }
    } catch {
      resolve([])
    }
  }
}

function handleNodeClick(data: TreeNode) {
  navigateTo(data.path)
}

// Navigation
function navigateTo(path: string) {
  currentPath.value = path
  currentPage.value = 1
  clearSelection()
  loadContent()
}

// Content loading
async function loadContent() {
  contentLoading.value = true
  try {
    const response = await listFiles(
      currentPath.value,
      currentPage.value,
      pageSize.value,
      sortBy.value
    )
    // Hide the .trash folder from the photo browser.
    directories.value = response.directories.filter((dir) => dir.name !== '.trash')
    files.value = response.files
    totalFiles.value = response.total_files
  } catch (error) {
    console.error('Failed to load content:', error)
    directories.value = []
    files.value = []
    totalFiles.value = 0
  } finally {
    contentLoading.value = false
    // Directory count/pagination can change the table's top offset, so
    // recompute the fill height once the new content is rendered.
    updateTableHeight()
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  clearSelection()
  loadContent()
}

// Preview
function openPreview(index: number) {
  previewIndex.value = index
  previewVisible.value = true
}

function handleRowClick(row: FileInfo, column: { type?: string }) {
  // Skip opening preview when the click originated from the checkbox column
  if (column?.type === 'selection') return
  const index = files.value.findIndex((f) => f.id === row.id)
  if (index >= 0) {
    openPreview(index)
  }
}

// Download
function handleDownloadClick(file: FileInfo) {
  downloadFile(file.id, file.file_name)
}

// Delete file
async function handleDeleteFile(file: FileInfo) {
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
    // Refresh content
    loadContent()
    trashStore.refresh()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// Delete directory
async function handleDeleteDirectory(dir: DirectoryInfo) {
  try {
    await ElMessageBox.confirm(
      `确定要将目录 "${dir.name}" 及其所有内容移入回收站吗？\n${configStore.trashRetentionDays} 天后自动彻底删除，可在回收站中恢复。`,
      '移入回收站',
      {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
    const result = await deleteDirectory(dir.path)
    ElMessage.success(result.message)
    loadContent()
    trashStore.refresh()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// Context menu handlers
function showContextMenu(event: MouseEvent, type: 'file' | 'directory', file?: FileInfo, dir?: DirectoryInfo) {
  event.preventDefault()
  event.stopPropagation()
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    type,
    file: file || null,
    directory: dir || null,
  }
}

function closeContextMenu() {
  contextMenu.value.visible = false
}

function handleContextDownload() {
  if (contextMenu.value.file) {
    downloadFile(contextMenu.value.file.id, contextMenu.value.file.file_name)
  }
  closeContextMenu()
}

async function handleContextDelete() {
  if (contextMenu.value.type === 'file' && contextMenu.value.file) {
    const file = contextMenu.value.file
    closeContextMenu()
    try {
      await ElMessageBox.confirm(
        `确定要将文件 "${file.file_name}" 移入回收站吗？\n${configStore.trashRetentionDays} 天后自动彻底删除，可在回收站中恢复。`,
        '移入回收站',
        {
          confirmButtonText: '移入回收站',
          cancelButtonText: '取消',
          type: 'warning',
        }
      )
      const result = await deleteFile(file.id)
      ElMessage.success(result.message)
      loadContent()
      trashStore.refresh()
    } catch (error: any) {
      if (error !== 'cancel') {
        ElMessage.error('操作失败')
      }
    }
  } else if (contextMenu.value.type === 'directory' && contextMenu.value.directory) {
    const dir = contextMenu.value.directory
    closeContextMenu()
    try {
      await ElMessageBox.confirm(
        `确定要将目录 "${dir.name}" 及其所有内容移入回收站吗？\n${configStore.trashRetentionDays} 天后自动彻底删除，可在回收站中恢复。`,
        '移入回收站',
        {
          confirmButtonText: '移入回收站',
          cancelButtonText: '取消',
          type: 'warning',
        }
      )
      const result = await deleteDirectory(dir.path)
      ElMessage.success(result.message)
      loadContent()
      trashStore.refresh()
    } catch (error: any) {
      if (error !== 'cancel') {
        ElMessage.error('操作失败')
      }
    }
  }
}

// Helpers
function getFileType(fileName: string): string {
  const ext = fileName.split('.').pop()?.toLowerCase() || ''
  const typeMap: Record<string, string> = {
    jpg: 'JPEG',
    jpeg: 'JPEG',
    png: 'PNG',
    webp: 'WebP',
    gif: 'GIF',
    heic: 'HEIC',
    heif: 'HEIF',
    avif: 'AVIF',
    bmp: 'BMP',
    tiff: 'TIFF',
    tif: 'TIFF',
    dng: 'RAW',
    cr2: 'RAW',
    cr3: 'RAW',
    nef: 'RAW',
    arw: 'RAW',
    orf: 'RAW',
    raf: 'RAW',
    rw2: 'RAW',
  }
  return typeMap[ext] || ext.toUpperCase()
}

// Initialize
onMounted(() => {
  configStore.ensureLoaded()
  loadContent()
  window.addEventListener('resize', updateTableHeight)
  updateTableHeight()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateTableHeight)
})
</script>

<style scoped>
/* 照片瓦片（pv-tile）、右键菜单（pv-context-menu）、区块标题（pv-section-title）、
   加载态（pv-loading）均来自 styles/layout.css，本页只保留目录树侧栏与
   目录卡片、列表视图这些独有的样式。 */
.photos-view,
.photos-container {
  height: 100%;
}

/* Sidebar */
.directory-sidebar {
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

.directory-tree {
  padding: 8px 0;
}

.directory-tree :deep(.el-tree-node__content) {
  padding-right: 12px;
}

/* Element Plus inserts its own loading icon between the expand arrow and the
   node content while children lazy-load, which pushes the folder icon/name to
   the right. We suppress that inserted icon and instead render our own spinner
   in the folder-icon slot (see the tree #default template), so the row keeps a
   stable layout while still showing loading feedback. */
.directory-tree :deep(.el-tree-node__loading-icon) {
  display: none;
}

.tree-node-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  max-width: 100%;
  overflow: hidden;
}

.tree-node-label span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-node-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 0 6px;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  background: #f2f3f5;
  border-radius: 9px;
  flex-shrink: 0;
}

/* 右侧内容区：标题栏固定，内容自己滚动（pv-page-head / pv-page-body），
   与探索页、分类照片页的行为一致。 */
.content-area {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 面包屑在标题栏里作为副信息展示 */
.path-breadcrumb :deep(.el-breadcrumb__item),
.path-breadcrumb :deep(.el-breadcrumb__inner) {
  cursor: pointer;
}

.path-breadcrumb :deep(.el-breadcrumb__inner:hover) {
  color: var(--el-color-primary);
}

/* Directories */
.directories-section {
  margin-bottom: 24px;
}

.directories-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--pv-gap);
}

.directory-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border-radius: var(--pv-radius);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
  border: 1px solid var(--pv-divider-color);
  position: relative;
}

.directory-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: var(--pv-hover-shadow);
}

.directory-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 1;
}

.directory-name {
  font-size: 14px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.directory-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* Files */
.files-section {
  margin-bottom: var(--pv-page-gutter);
}

/* List view */
.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: nowrap;
}

.row-actions .el-button {
  margin: 0;
}

.list-thumbnail {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}

.list-thumbnail-wrap {
  position: relative;
  width: 40px;
  height: 40px;
}

.list-video-badge {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
  filter: drop-shadow(0 0 2px rgba(0, 0, 0, 0.8));
  pointer-events: none;
}

/* Pagination */
.pagination-container {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}
</style>
