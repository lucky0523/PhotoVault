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
              <el-icon v-if="data.isDevice"><Monitor /></el-icon>
              <el-icon v-else><Folder /></el-icon>
              <span>{{ node.label }}</span>
              <span v-if="data.file_count" class="tree-node-count">{{ data.file_count }}</span>
            </span>
          </template>
        </el-tree>
      </el-aside>

      <!-- Right content area -->
      <el-main class="content-area">
        <!-- Toolbar -->
        <div class="toolbar">
          <div class="toolbar-left">
            <!-- Breadcrumb -->
            <el-breadcrumb separator="/">
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
          </div>

          <div class="toolbar-right">
            <!-- Batch actions: shown whenever there's a selection -->
            <template v-if="selectedIds.size > 0">
              <span class="selection-count">已选择 {{ selectedIds.size }} 项</span>
              <el-button size="small" @click="selectAllFiles">全选</el-button>
              <el-button size="small" @click="clearSelection">取消选择</el-button>
              <el-button type="primary" size="small" @click="handleBatchDownload">
                <el-icon><Download /></el-icon>
                下载
              </el-button>
              <el-button type="danger" size="small" @click="handleBatchDelete">
                <el-icon><Delete /></el-icon>
                移入回收站
              </el-button>
            </template>

            <!-- Sort dropdown -->
            <el-select v-model="sortBy" size="small" style="width: 120px" @change="loadContent">
              <el-option label="名称" value="name" />
              <el-option label="大小" value="size" />
              <el-option label="时间" value="time" />
            </el-select>

            <!-- View toggle -->
            <el-button-group>
              <el-button
                :type="viewMode === 'grid' ? 'primary' : 'default'"
                size="small"
                @click="setViewMode('grid')"
              >
                <el-icon><Grid /></el-icon>
              </el-button>
              <el-button
                :type="viewMode === 'list' ? 'primary' : 'default'"
                size="small"
                @click="setViewMode('list')"
              >
                <el-icon><List /></el-icon>
              </el-button>
            </el-button-group>
          </div>
        </div>

        <!-- Loading state -->
        <div v-if="contentLoading" class="loading-container">
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
            <div class="section-title">文件夹</div>
            <!-- Grid view -->
            <div v-if="viewMode === 'grid'" class="directories-grid">
              <div
                v-for="dir in directories"
                :key="dir.path"
                class="directory-card"
                @click="navigateTo(dir.path)"
                @contextmenu="showContextMenu($event, 'directory', undefined, dir)"
              >
                <el-icon :size="32" color="#e6a23c"><Folder /></el-icon>
                <div class="directory-info">
                  <span class="directory-name">{{ dir.name }}</span>
                  <span class="directory-count">{{ dir.file_count }} 个文件</span>
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
                  <el-icon :size="20" color="#e6a23c"><Folder /></el-icon>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="名称" min-width="200" />
              <el-table-column label="文件数" width="100">
                <template #default="{ row }">
                  {{ row.file_count }}
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
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" link size="small" @click.stop="handleDeleteDirectory(row)">
                    移入回收站
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- Files section -->
          <div v-if="files.length > 0" class="files-section">
            <div class="section-title">文件</div>
            
            <!-- Grid view -->
            <div v-show="viewMode === 'grid'" class="files-grid">
              <div
                v-for="(file, index) in files"
                :key="file.id"
                class="file-card"
                :class="{ 'is-selected': selectedIds.has(file.id) }"
                @click="handleCardClick(file, index)"
                @contextmenu="showContextMenu($event, 'file', file)"
              >
                <div
                  class="file-checkbox"
                  :class="{ 'is-checked': selectedIds.has(file.id) }"
                  @click.stop="toggleSelect(file.id)"
                >
                  <el-icon v-if="selectedIds.has(file.id)"><Check /></el-icon>
                </div>
                <div class="file-thumbnail">
                  <img
                    :src="getThumbnailUrl(file.id, 'small')"
                    :alt="file.file_name"
                    loading="lazy"
                    @error="handleThumbnailError"
                  />
                </div>
                <div class="file-overlay">
                  <span class="file-name">{{ file.file_name }}</span>
                  <span class="file-size">{{ formatFileSize(file.file_size) }}</span>
                </div>
              </div>
            </div>

            <!-- List view -->
            <div v-show="viewMode === 'list'" class="list-view-container">
              <el-table
                ref="fileTableRef"
                :data="files"
                height="500"
                style="width: 100%"
                row-key="id"
                @row-click="(row: FileInfo, column: any) => handleRowClick(row, column)"
                @row-contextmenu="(row: FileInfo, event: MouseEvent) => showContextMenu(event, 'file', row)"
                @selection-change="handleTableSelectionChange"
              >
              <el-table-column type="selection" width="50" :selectable="() => true" />
              <el-table-column width="60">
                <template #default="{ row }">
                  <img
                    :src="getThumbnailUrl(row.id, 'small')"
                    class="list-thumbnail"
                    :alt="row.file_name"
                    @error="handleThumbnailError"
                  />
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
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <template v-if="contextMenu.type === 'file'">
          <div class="context-menu-item" @click="handleContextDownload">
            <el-icon><Download /></el-icon>
            下载
          </div>
          <div class="context-menu-item danger" @click="handleContextDelete">
            <el-icon><Delete /></el-icon>
            移入回收站
          </div>
        </template>
        <template v-else-if="contextMenu.type === 'directory'">
          <div class="context-menu-item danger" @click="handleContextDelete">
            <el-icon><Delete /></el-icon>
            移入回收站
          </div>
        </template>
      </div>
      <div v-if="contextMenu.visible" class="context-menu-overlay" @click="closeContextMenu" />
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
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

function setViewMode(mode: 'grid' | 'list') {
  viewMode.value = mode
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
      `确定要将选中的 ${targets.length} 个文件移入回收站吗？\n30 天后自动彻底删除，可在回收站中恢复。`,
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
      const nodes: TreeNode[] = response.directories.map((dir) => ({
        label: dir.name,
        path: dir.path,
        isLeaf: false,
        isDevice: true,
        file_count: dir.file_count,
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
      const nodes: TreeNode[] = response.directories.map((dir) => ({
        label: dir.name,
        path: dir.path,
        isLeaf: dir.file_count === 0 && response.directories.length === 0,
        file_count: dir.file_count,
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
    directories.value = response.directories
    files.value = response.files
    totalFiles.value = response.total_files
  } catch (error) {
    console.error('Failed to load content:', error)
    directories.value = []
    files.value = []
    totalFiles.value = 0
  } finally {
    contentLoading.value = false
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
      `确定要将文件 "${file.file_name}" 移入回收站吗？\n30 天后自动彻底删除，可在回收站中恢复。`,
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
      `确定要将目录 "${dir.name}" 及其所有内容移入回收站吗？\n30 天后自动彻底删除，可在回收站中恢复。`,
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
        `确定要将文件 "${file.file_name}" 移入回收站吗？\n30 天后自动彻底删除，可在回收站中恢复。`,
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
        `确定要将目录 "${dir.name}" 及其所有内容移入回收站吗？\n30 天后自动彻底删除，可在回收站中恢复。`,
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

function handleThumbnailError(e: Event) {
  const img = e.target as HTMLImageElement
  img.src = 'data:image/svg+xml,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect fill="#f0f0f0" width="200" height="200"/><text x="100" y="100" text-anchor="middle" fill="#999" font-size="14">无缩略图</text></svg>'
  )
}

// Initialize
onMounted(() => {
  loadContent()
})
</script>

<style scoped>
.photos-view {
  height: 100%;
}

.photos-container {
  height: 100%;
}

/* Sidebar */
.directory-sidebar {
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

.directory-tree {
  padding: 8px 0;
}

.directory-tree :deep(.el-tree-node__content) {
  padding-right: 12px;
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

.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: nowrap;
}

.row-actions .el-button {
  margin: 0;
}

.tree-node-count {
  color: #909399;
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
  padding: 8px 12px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.toolbar-left {
  display: flex;
  align-items: center;
}

.toolbar-left :deep(.el-breadcrumb__item) {
  cursor: pointer;
}

.toolbar-left :deep(.el-breadcrumb__inner) {
  cursor: pointer;
}

.toolbar-left :deep(.el-breadcrumb__inner:hover) {
  color: var(--el-color-primary);
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

/* Directories */
.section-title {
  font-size: 13px;
  color: #909399;
  margin-bottom: 12px;
  font-weight: 500;
}

.directories-section {
  margin-bottom: 24px;
}

.directories-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.directory-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #e4e7ed;
  position: relative;
}

.directory-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.directory-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 1;
}

.directory-name {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.directory-count {
  font-size: 12px;
  color: #909399;
}

/* Files grid */
.files-section {
  margin-bottom: 16px;
}

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

.file-card.is-selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary);
}

.file-checkbox {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 2;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.9);
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  opacity: 0;
  transition: opacity 0.15s, background-color 0.15s, border-color 0.15s;
  cursor: pointer;
}

.file-card:hover .file-checkbox,
.file-checkbox.is-checked {
  opacity: 1;
}

.file-checkbox:hover {
  background: rgba(0, 0, 0, 0.4);
}

.file-checkbox.is-checked {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.selection-count {
  font-size: 13px;
  color: #606266;
  margin-right: 4px;
  white-space: nowrap;
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

.file-overlay {
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

.file-name {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.8);
}

/* List view */
.list-thumbnail {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}

/* Pagination */
.pagination-container {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}

/* Context Menu */
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
