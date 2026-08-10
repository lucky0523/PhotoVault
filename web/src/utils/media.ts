/**
 * 媒体类型判定与缩略图兜底。
 *
 * 这三段逻辑此前在 PhotosView / TimelineView / CategoryPhotosView / ExploreView
 * 里各抄了一份（视频扩展名表在三处重复、占位图 SVG 在四处重复），任何一处调整
 * 都会让页面之间的表现不一致，所以统一收敛到这里。
 */

/** 判定媒体类型所需的最小字段，兼容 FileInfo / ExplorePhoto / TrashItem。 */
export interface MediaLike {
  file_name: string
  mime_type?: string
  media_type?: string
  is_motion_photo?: boolean
}

const VIDEO_EXTENSIONS = [
  'mp4', 'mov', 'mkv', 'webm', '3gp', 'avi', 'mpeg', 'mpg',
  'wmv', 'flv', 'm4v', 'ts', 'm2ts', 'mts',
]

/** 是否为视频：优先信任服务端的 media_type / mime_type，兜底看扩展名。 */
export function isVideo(file: MediaLike): boolean {
  if ((file.media_type || '').toLowerCase() === 'video') return true
  if (file.mime_type && file.mime_type.toLowerCase().startsWith('video/')) return true
  const ext = file.file_name.split('.').pop()?.toLowerCase() || ''
  return VIDEO_EXTENSIONS.includes(ext)
}

/** 是否为动态照片（Live Photo / Motion Photo）。视频不算。 */
export function isMotionPhoto(file: MediaLike): boolean {
  return !isVideo(file) && !!file.is_motion_photo
}

/** 缩略图缺失时的内联占位图。 */
export const THUMBNAIL_FALLBACK =
  'data:image/svg+xml,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">' +
      '<rect fill="#f0f0f0" width="200" height="200"/>' +
      '<text x="100" y="100" text-anchor="middle" fill="#999" font-size="14">无缩略图</text>' +
      '</svg>'
  )

/** <img @error> 处理器：换成占位图，避免出现浏览器默认的破图图标。 */
export function handleThumbnailError(e: Event): void {
  const img = e.target as HTMLImageElement
  if (img.src === THUMBNAIL_FALLBACK) return
  img.src = THUMBNAIL_FALLBACK
}
