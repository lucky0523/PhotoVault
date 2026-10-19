import axios from 'axios'
import http from './http'
import { getAccessToken } from '@/utils/authTokens'

export interface DirectoryInfo {
  name: string
  path: string
  file_count: number
  size: number
  latest_file_time?: string
  // Per-status counts. file_count is the total across all statuses;
  // backed_up_count reflects only active (non-trashed, non-purged) files.
  backed_up_count: number
  trashed_count: number
  purged_count: number
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
  media_type?: string
  is_motion_photo?: boolean
  is_ultra_hdr?: boolean
  exif_time?: string
  thumbnail_url: string
  created_at: string
  device_name?: string
  focal_length?: number
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

export interface FilesConfig {
  trash_retention_days: number
}

/**
 * Get client-facing file/trash configuration (e.g. trash retention days)
 */
export async function getFilesConfig(): Promise<FilesConfig> {
  const response = await http.get('/files/config')
  return response.data
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

// ---------------------------------------------------------------------------
// Timeline API（时间线专用：按月份分页，搜索与筛选都在服务端执行）
// ---------------------------------------------------------------------------

/** 时间线的查询条件（标题栏的搜索框与各筛选器）。 */
export interface TimelineQuery {
  q?: string
  devices?: string[]
  formats?: string[]
  focals?: string[]
  dateFrom?: string
  dateTo?: string
  /** 虚拟目录，'' / undefined 表示全部（含自定义存储路径），与数据统计的目录范围同语义 */
  path?: string
  /** 是否包含子目录，默认 true；只在 path 非空时生效 */
  recursive?: boolean
  /** 单日拍摄日期 YYYY-MM-DD，按数据统计口径（拍摄时间、当地时间）筛选 */
  captureDate?: string
  tz?: string
}

export interface TimelineMonth {
  year: number
  month: number
  count: number
}

export interface TimelineSummary {
  tz: string
  total_count: number
  filtered_count: number
  months: TimelineMonth[]
}

export interface TimelineFacets {
  tz: string
  devices: string[]
  formats: string[]
  focal_buckets: string[]
  photo_dates: string[]
}

export interface TimelineFilesPage {
  month: string
  files: FileInfo[]
  has_more: boolean
}

/** 月份内的一页：向下续页用 keyset（afterTime/afterId），跳转和向上补洞用 offset。 */
export interface TimelinePageRequest {
  month: string
  limit: number
  offset?: number
  afterTime?: string
  afterId?: number
}

/** 把 TimelineQuery 转成接口参数；空数组、空串、undefined 都不发送。 */
function timelineParams(query: TimelineQuery): Record<string, string | string[]> {
  const params: Record<string, string | string[]> = {}
  if (query.q) params.q = query.q
  if (query.devices && query.devices.length > 0) params.device = query.devices
  if (query.formats && query.formats.length > 0) params.format = query.formats
  if (query.focals && query.focals.length > 0) params.focal = query.focals
  if (query.dateFrom) params.date_from = query.dateFrom
  if (query.dateTo) params.date_to = query.dateTo
  // 根目录不发 path；recursive 默认为 true，只在选了目录且不含子目录时发 false
  if (query.path) {
    params.path = query.path
    if (query.recursive === false) params.recursive = 'false'
  }
  if (query.captureDate) params.capture_date = query.captureDate
  if (query.tz) params.tz = query.tz
  return params
}

// axios 默认把数组序列化成 device[]=A，FastAPI 不认；indexes: null 才是 device=A&device=B。
const REPEAT_KEYS = { indexes: null }

/** 全库的设备 / 格式 / 焦段选项和“有照片的日期”，不受筛选影响。 */
export async function getTimelineFacets(
  tz: string | undefined,
  signal?: AbortSignal
): Promise<TimelineFacets> {
  const response = await http.get('/files/timeline/facets', {
    params: tz ? { tz } : {},
    paramsSerializer: REPEAT_KEYS,
    signal,
  })
  return response.data
}

/** 筛选后的月份计数（时间轴与骨架）以及全库张数。 */
export async function getTimelineSummary(
  query: TimelineQuery,
  signal?: AbortSignal
): Promise<TimelineSummary> {
  const response = await http.get('/files/timeline/summary', {
    params: timelineParams(query),
    paramsSerializer: REPEAT_KEYS,
    signal,
  })
  return response.data
}

/** 某个月份里的一页文件，按时间倒序。 */
export async function listTimelineFiles(
  query: TimelineQuery,
  page: TimelinePageRequest,
  signal?: AbortSignal
): Promise<TimelineFilesPage> {
  const params: Record<string, string | string[] | number> = {
    ...timelineParams(query),
    month: page.month,
    limit: page.limit,
  }
  if (page.afterTime !== undefined && page.afterId !== undefined) {
    params.after_time = page.afterTime
    params.after_id = page.afterId
  } else {
    params.offset = page.offset ?? 0
  }
  const response = await http.get('/files/timeline/files', {
    params,
    paramsSerializer: REPEAT_KEYS,
    signal,
  })
  return response.data
}

/** 服务端不认识浏览器上报的时区（422 invalid_tz）。 */
export function isInvalidTzError(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  if (error.response?.status !== 422) return false
  const detail = (error.response.data as { detail?: unknown } | undefined)?.detail
  return (
    typeof detail === 'object' &&
    detail !== null &&
    (detail as { code?: unknown }).code === 'invalid_tz'
  )
}

function buildAuthenticatedMediaUrl(
  path: string,
  params?: Record<string, string>
): string {
  const query = new URLSearchParams(params)
  // Read at call time so refreshed credentials are never captured at module load.
  const token = getAccessToken()
  if (token) query.set('token', token)
  const queryString = query.toString()
  return queryString ? `${path}?${queryString}` : path
}

/**
 * Get thumbnail URL for a file
 */
export function getThumbnailUrl(fileId: number, size: 'small' | 'medium' = 'small'): string {
  return buildAuthenticatedMediaUrl(`/api/v1/files/thumbnail/${fileId}`, { size })
}

/**
 * Get download URL for a file
 */
export function getDownloadUrl(fileId: number): string {
  return buildAuthenticatedMediaUrl(`/api/v1/files/download/${fileId}`)
}

/**
 * Get the embedded motion-photo video URL (动态照片) for a file
 */
export function getMotionVideoUrl(fileId: number): string {
  return buildAuthenticatedMediaUrl(`/api/v1/files/motion/${fileId}`)
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
 * Normalize a backend timestamp string before handing it to `Date`.
 *
 * `created_at` comes from SQLite's `CURRENT_TIMESTAMP`, which is documented to
 * be UTC but is formatted as "YYYY-MM-DD HH:MM:SS" (space-separated) with no
 * timezone marker. The JS `Date` constructor treats a marker-less string as
 * *local* time, so parsing it as-is silently shifts the displayed time by the
 * browser's UTC offset.
 *
 * `exif_time`, by contrast, is written by the client as "yyyy-MM-ddTHH:mm:ss"
 * (T-separated) and represents the photo's local wall-clock time at capture
 * with no reliable timezone info of its own — it must NOT be reinterpreted as
 * UTC, so it's left untouched and continues to be parsed as local time.
 *
 * This only tags strings that (a) have no explicit timezone/offset and
 * (b) use the space-separated SQLite format, so `Date` — and therefore
 * `toLocaleDateString` — converts and displays them in the browser's local
 * timezone correctly.
 */
export function normalizeTimestamp(dateStr: string): string {
  const hasTimezone = /[Zz]|[+-]\d{2}:?\d{2}$/.test(dateStr)
  const isSqliteUtcFormat = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$/.test(dateStr)
  if (!hasTimezone && isSqliteUtcFormat) {
    return dateStr.replace(' ', 'T') + 'Z'
  }
  return dateStr
}

/**
 * Format date string to locale display (using the browser's local timezone).
 */
export function formatDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '-'
  const date = new Date(normalizeTimestamp(dateStr))
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
