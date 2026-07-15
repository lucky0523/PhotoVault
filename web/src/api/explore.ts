import http from './http'
import type { FileInfo } from './files'

// Re-export the thumbnail URL helper so explore views can import it from here
// (design: explore.ts reuses files.ts getThumbnailUrl).
export { getThumbnailUrl } from './files'

// ---------------------------------------------------------------------------
// Types (aligned with server/app/api/explore.py response models)
// ---------------------------------------------------------------------------

/** A person (face cluster) entry in the people aggregation. */
export interface PersonCluster {
  cluster_id: number
  name: string
  face_count: number
  cover_file_id?: number
  cover_face_bbox?: string
}

/** A city grouping in the places aggregation. */
export interface PlaceGroup {
  city: string
  province?: string
  country?: string
  count: number
  cover_file_id?: number
}

/** A scene-label grouping in the scenes aggregation. */
export interface SceneGroup {
  label: string
  name_zh: string
  count: number
  cover_file_id?: number
}

/** A photo under a person / place / scene (aligned with FileInfo). */
export type ExplorePhoto = FileInfo

/** Paginated list of photos under a person / place / scene. */
export interface ExplorePhotosResponse {
  files: ExplorePhoto[]
  total: number
  page: number
  page_size: number
}

/** Installation state of a single AnalysisResource type. */
export interface ResourceStatus {
  type: string
  installed: boolean
  name?: string
  version?: string
  size: number
  updated_at?: string
  enabled: boolean
}

/** Status of the three AnalysisResource types. */
export interface ResourcesResponse {
  face: ResourceStatus
  scene: ResourceStatus
  geocoding: ResourceStatus
}

/** Result of a person rename. */
export interface RenamePersonResponse {
  success: boolean
  name: string
}

/** Result of an AnalysisResource upload. */
export interface ResourceUploadResponse {
  success: boolean
  message: string
  status: ResourceStatus
}

/** Result of triggering a re-analysis of existing photos. */
export interface ReanalyzeResponse {
  success: boolean
  queued: number
}

// ---------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------

/** List person clusters, optionally filtered by library (device_name). */
export async function getPeople(library?: string): Promise<PersonCluster[]> {
  const response = await http.get('/explore/people', {
    params: { library: library || undefined },
  })
  return response.data
}

/** List photos containing a face in the given cluster (paginated). */
export async function getPeoplePhotos(
  clusterId: number,
  page: number = 1,
  pageSize: number = 50,
  library?: string
): Promise<ExplorePhotosResponse> {
  const response = await http.get(`/explore/people/${clusterId}`, {
    params: { page, page_size: pageSize, library: library || undefined },
  })
  return response.data
}

/** Set/modify the display name of a person cluster. */
export async function renamePerson(
  clusterId: number,
  name: string
): Promise<RenamePersonResponse> {
  const response = await http.put(`/explore/people/${clusterId}`, { name })
  return response.data
}

// ---------------------------------------------------------------------------
// Places
// ---------------------------------------------------------------------------

/** List cities aggregated from photo GPS, optionally filtered by library. */
export async function getPlaces(library?: string): Promise<PlaceGroup[]> {
  const response = await http.get('/explore/places', {
    params: { library: library || undefined },
  })
  return response.data
}

/** List photos taken in the given city (paginated). */
export async function getPlacePhotos(
  city: string,
  page: number = 1,
  pageSize: number = 50,
  library?: string
): Promise<ExplorePhotosResponse> {
  const response = await http.get(`/explore/places/${encodeURIComponent(city)}`, {
    params: { page, page_size: pageSize, library: library || undefined },
  })
  return response.data
}

// ---------------------------------------------------------------------------
// Scenes
// ---------------------------------------------------------------------------

/** List scene labels aggregated from photo_scenes, optionally filtered. */
export async function getScenes(library?: string): Promise<SceneGroup[]> {
  const response = await http.get('/explore/scenes', {
    params: { library: library || undefined },
  })
  return response.data
}

/** List photos classified with the given scene label (paginated). */
export async function getScenePhotos(
  label: string,
  page: number = 1,
  pageSize: number = 50,
  library?: string
): Promise<ExplorePhotosResponse> {
  const response = await http.get(`/explore/scenes/${encodeURIComponent(label)}`, {
    params: { page, page_size: pageSize, library: library || undefined },
  })
  return response.data
}

// ---------------------------------------------------------------------------
// Resource management
// ---------------------------------------------------------------------------

/** Return the install status of the three AnalysisResource types. */
export async function getResources(): Promise<ResourcesResponse> {
  const response = await http.get('/explore/resources')
  return response.data
}

/** Upload/update an AnalysisResource file (admin only, multipart form data). */
export async function uploadResource(
  type: string,
  file: File
): Promise<ResourceUploadResponse> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await http.post(`/explore/resources/${type}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return response.data
}

/** Ack for a started background download. */
export interface DownloadStartResponse {
  started: boolean
}

/** Progress snapshot for an in-flight (or last) resource download. */
export interface DownloadProgress {
  status: 'idle' | 'running' | 'success' | 'error'
  phase: 'downloading' | 'installing' | ''
  downloaded: number
  total?: number | null
  percent?: number | null
  message: string
  error?: string | null
  resource?: ResourceStatus | null
}

/** Start a background download/update of an AnalysisResource (admin only). */
export async function downloadResource(
  type: string,
  url: string
): Promise<DownloadStartResponse> {
  const response = await http.post(`/explore/resources/${type}/download`, { url })
  return response.data
}

/** Poll the current download progress for a resource type. */
export async function getDownloadProgress(type: string): Promise<DownloadProgress> {
  const response = await http.get(`/explore/resources/${type}/download/progress`)
  return response.data
}

/** Trigger re-analysis of existing photos (admin only). */
export async function reanalyze(dimensions?: string[]): Promise<ReanalyzeResponse> {
  const response = await http.post('/explore/reanalyze', { dimensions })
  return response.data
}

/** The three analysis feature flags (people / places / scenes). */
export interface AnalysisFlags {
  enable_place: boolean
  enable_scene: boolean
  enable_face: boolean
}

/** Get the current analysis feature flags. */
export async function getAnalysisSettings(): Promise<AnalysisFlags> {
  const response = await http.get('/explore/settings')
  return response.data
}

/** Per-dimension analysis status counts. */
export interface DimensionStatus {
  enabled: boolean
  installed: boolean
  photos: number
  groups: number
}

/** Overall analysis status for the current user's library. */
export interface AnalysisStatus {
  total_images: number
  queue_pending: number
  runtime_ok: boolean
  people: DimensionStatus
  places: DimensionStatus
  scenes: DimensionStatus
}

/** Get analysis progress counts for the current user. */
export async function getAnalysisStatus(): Promise<AnalysisStatus> {
  const response = await http.get('/explore/status')
  return response.data
}

/** Enable/disable analysis dimensions at runtime (admin only, no restart). */
export async function updateAnalysisSettings(
  flags: AnalysisFlags
): Promise<AnalysisFlags> {
  const response = await http.put('/explore/settings', flags)
  return response.data
}

/**
 * Default download URLs per resource type, pre-filled in the manage UI.
 *
 * - face: InsightFace buffalo_l model pack (.zip → det_*.onnx + w600k_*.onnx).
 *   Note: buffalo_l is licensed for non-commercial use.
 * - geocoding: GeoNames cities (population > 15000) dump; the server converts
 *   the contained tab-separated file into cities.db.
 * - scene: no canonical prebuilt ONNX exists (Places365 ships PyTorch weights
 *   that must be exported), so this is left blank for the user to fill in.
 */
export const DEFAULT_RESOURCE_URLS: Record<string, string> = {
  face: 'https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip',
  scene: '',
  geocoding: 'https://download.geonames.org/export/dump/cities15000.zip',
}
