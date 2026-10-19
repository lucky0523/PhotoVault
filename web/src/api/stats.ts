import http from './http'

/**
 * GET /stats/media 的响应类型（设计 §6.2，schema_version = 1）。
 *
 * 服务端用 response_model_exclude_none，声明了默认值 None 的字段不适用时直接省略，
 * 所以这里一律写成可选字段。kind 用 string 而不是联合类型：以后服务端可能新增
 * kind 取值，前端按卡片配置比对，不认识的直接跳过。目前已有的 kind：
 * "distribution" / "ranking" / "series"（Distribution），以及 overview.daily 的 "calendar"。
 */
export interface StatBucket {
  key: string
  label: string
  count: number
  /** 百分数，保留 1 位小数（62.5 表示 62.5%） */
  share: number
  /** 区间说明，只有 iso / focal_length / aperture / shutter / scene_ev / duration 有 */
  range?: string
  /** 只有 overview.categories / formats（含 children）有 */
  bytes?: number
  bytes_share?: number
  /** 只有 formats 的 raw / video_other / misc 组有 */
  children?: StatBucket[]
}

export interface Distribution {
  /** 本期为 "distribution" / "ranking" / "series" */
  kind: string
  buckets: StatBucket[]
  unknown: number
  unknown_share: number
  /** 只有 kind="ranking" 时出现：Top N 之外的合计 */
  other?: number
  other_share?: number
  /** = Σ buckets.count + (other ?? 0) + unknown */
  total: number
  /** 只有 overview.categories / formats 有 */
  total_bytes?: number
  /** weekday / hour：{ local, utc_converted }；focal_length：{ by_exif, by_derived } */
  meta?: Record<string, number>
  /** 只有 photo.scene_ev 有：有效样本数、中位数 / 平均场景亮度 EV100（= ev − log2(ISO/100)，未取整；无样本时为 null） */
  summary?: { samples: number; median: number | null; mean: number | null }
}

export interface StatsScope {
  path: string
  recursive: boolean
  /** 实际使用的默认时区：IANA 名或 "UTC+08:00" */
  tz: string
  tz_source: 'config' | 'request' | 'server'
  includes_custom_paths: boolean
}

export interface StatsNotice {
  code: string
  message: string
}

export interface OverviewTotal {
  count: number
  bytes: number
}

/**
 * overview.daily：按当地拍摄日期统计的每日照片 + 视频数（kind = "calendar"）。
 * days 只包含文件数 > 0 的日子（升序）；Σ days + unknown = total。
 * today 是统计时区的当地日期，前端不做时区换算。
 */
export interface DailyCounts {
  kind: string
  /** YYYY-MM-DD */
  today: string
  /** { 'YYYY-MM-DD': 文件数 } */
  days: Record<string, number>
  /** 没有可用拍摄时间的照片 + 视频数（热力图不显示） */
  unknown: number
  total: number
}

export interface StatsOverview {
  total: OverviewTotal
  motion_photo_count: number
  categories: Distribution
  formats: Distribution
  /** 旧版服务端没有这个字段 */
  daily?: DailyCounts
}

export interface MediaStatsResponse {
  schema_version: number
  scope: StatsScope
  /** UTC ISO 时间 */
  generated_at: string
  pending_metadata_count: number
  notices: StatsNotice[]
  overview: StatsOverview
  photo: Record<string, Distribution>
  video: Record<string, Distribution>
}

export interface MediaStatsParams {
  path: string
  recursive: boolean
  tz?: string
}

/**
 * 获取指定目录的媒体统计。
 *
 * 整库统计在大库上可能较慢，超时放宽到 60 秒；signal 用于切换筛选时取消上一次请求。
 */
export async function getMediaStats(
  params: MediaStatsParams,
  signal: AbortSignal
): Promise<MediaStatsResponse> {
  const response = await http.get<MediaStatsResponse>('/stats/media', {
    params,
    signal,
    timeout: 60000,
  })
  return response.data
}
