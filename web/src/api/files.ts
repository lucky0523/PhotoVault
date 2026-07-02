import http from './http'

export interface DirectoryInfo {
  name: string
  path: string
  file_count: number
  size: number
  latest_file_time?: string
}

export interface DeviceStats {
  name: string
  path: string
  backed_up_count: number
  trashed_count: number
  purged_count: number
  file_count: number
  latest_file_time?: string
}

export interface FileInfo {
  id: number
  file_name: string
  file_size: number
  mime_type?: string
  exif_time?: string
  thumbnail_url: string
  created_at: string
}

export interface BrowseResponse {
  current_path: string
  parent_path: string | null
  directories: DirectoryInfo[]
  files: FileInfo[]
  total_files: number
  page: number
  page_size: number
}

export interface ListResponse {
  current_path: string
  parent_path: string | null
  directories: DirectoryInfo[]
  files: FileInfo[]
  total_files: number
  page: number
  page_size: number
}

export interface DeleteFileResponse {
  success: boolean
  message: string
}

export interface DeleteDirectoryResponse {
  success: boolean
  deleted_count: number
  message: string
}

export interface TrashItem {
  id: number
  file_name: string
  file_size: number
  file_path: string
  original_path: string
  display_path: string
  device_name: string
  mime_type?: string
  media_type: string
  exif_time?: string
  created_at: string
  deleted_at: string
  deleted_batch_id: string
  expires_at?: string
  is_reference: boolean
}

export interface TrashListResponse {
  items: TrashItem[]
  total: number
  page: number
  page_size: number
}

export interface TrashActionResponse {
  success: boolean
  message: string
  count?: number
}

export interface StatusSyncItem {
  file_hash: string
  status: string
  deleted_at?: string
  purged_at?: string
  expires_at?: string
}

export interface StatusSyncResponse {
  items: StatusSyncItem[]
}

/**
 * Browse directory structure (directories + files)
 */
export async function browseFiles(
  path: string = '',
  page: number = 1,
  pageSize: number = 50
): Promise<BrowseResponse> {
  const response = await http.get('/files/browse', {
    params: { path, page, page_size: pageSize },
  })
  return response.data
}

/**
 * Get per-device file counts broken down by status (backed up / trashed / purged)
 */
export async function getDeviceStats(): Promise<DeviceStats[]> {
  const response = await http.get('/files/devices')
  return response.data
}

/**
 * List files in a directory with sorting
 */
export async function listFiles(
  path: string = '',
  page: number = 1,
  pageSize: number = 50,
  sortBy: string = 'name'
): Promise<ListResponse> {
  const response = await http.get('/files/list', {
    params: { path, page, page_size: pageSize, sort_by: sortBy },
  })
  return response.data
}

/**
 * Get thumbnail URL for a file
 */
export function getThumbnailUrl(fileId: number, size: 'small' | 'medium' = 'small'): string {
  const token = localStorage.getItem('access_token')
  return `/api/v1/files/thumbnail/${fileId}?size=${size}&token=${token}`
}

/**
 * Get download URL for a file
 */
export function getDownloadUrl(fileId: number): string {
  const token = localStorage.getItem('access_token')
  return `/api/v1/files/download/${fileId}?token=${token}`
}

/**
 * Trigger file download in browser
 */
export function downloadFile(fileId: number, fileName: string): void {
  const url = getDownloadUrl(fileId)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

/**
 * Delete a single file
 */
export async function deleteFile(fileId: number): Promise<DeleteFileResponse> {
  const response = await http.delete(`/files/${fileId}`)
  return response.data
}

/**
 * Delete a directory and all its contents
 */
export async function deleteDirectory(path: string): Promise<DeleteDirectoryResponse> {
  const response = await http.delete('/files/directory', {
    params: { path },
  })
  return response.data
}

/**
 * Format file size to human-readable string
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}

/**
 * Format date string to locale display
 */
export function formatDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatRemainingTime(expiresAt: string | undefined | null): string {
  if (!expiresAt) return '-'
  const now = new Date()
  const expires = new Date(expiresAt)
  const diff = expires.getTime() - now.getTime()
  
  if (diff <= 0) return '已过期'
  
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
  
  if (days > 0) {
    if (hours > 0) {
      return `${days}天${hours}小时`
    }
    return `${days}天`
  }
  
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
  if (minutes > 0) {
    return `${minutes}分钟`
  }
  
  return '即将过期'
}

// ---------------------------------------------------------------------------
// Trash (recycle bin) API
// ---------------------------------------------------------------------------

export async function listTrash(page: number = 1, pageSize: number = 50): Promise<TrashListResponse> {
  const response = await http.get('/files/trash', {
    params: { page, page_size: pageSize },
  })
  return response.data
}

export async function restoreFile(fileId: number): Promise<TrashActionResponse> {
  const response = await http.post(`/files/trash/${fileId}/restore`)
  return response.data
}

export async function restoreBatch(batchId: string): Promise<TrashActionResponse> {
  const response = await http.post(`/files/trash/batch/${batchId}/restore`)
  return response.data
}

export async function purgeFile(fileId: number): Promise<TrashActionResponse> {
  const response = await http.delete(`/files/trash/${fileId}`)
  return response.data
}

export async function purgeAll(): Promise<TrashActionResponse> {
  const response = await http.delete('/files/trash')
  return response.data
}
