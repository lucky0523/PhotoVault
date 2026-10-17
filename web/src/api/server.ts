import http from './http'

export interface ServerInfo {
  lan_ips: string[]
  port: number
}

/**
 * Storage paths and disk usage. Only returned to admin users.
 *
 * The four locations are configured independently; `storage_root` is just the
 * fallback base for whichever ones the deployment left unset. Disk usage refers
 * to the volume holding `media_root`.
 */
export interface ServerStorageInfo {
  storage_root: string
  media_root: string
  database_path: string
  log_dir: string
  models_root: string
  total_gb: number
  used_gb: number
  available_gb: number
  /** Stored file versions (old content kept 30 days); null if it could not be read. */
  versions_count: number | null
  /** Total size in bytes of every stored version, expired or not; null if unknown. */
  versions_bytes: number | null
}

/** Effective runtime configuration. Only returned to admin users. */
export interface ServerConfigInfo {
  max_users: number
  allow_registration: boolean
  chunk_size_mb: number
  session_expire_days: number
  trash_retention_days: number
  access_token_expire_hours: number
  refresh_token_expire_days: number
  log_level: string
  enable_place: boolean
  enable_scene: boolean
  enable_face: boolean
}

export interface ServerAbout {
  name: string
  version: string
  api_version: string
  started_at: string
  uptime_seconds: number
  python_version: string
  fastapi_version: string
  platform: string
  machine: string
  hostname: string
  port: number
  lan_ips: string[]
  /** null for non-admin users */
  storage: ServerStorageInfo | null
  /** null for non-admin users */
  config: ServerConfigInfo | null
}

/**
 * Get server LAN IP addresses and port (for QR code generation)
 */
export async function getServerInfo(): Promise<ServerInfo> {
  const response = await http.get('/server/info')
  return response.data
}

/**
 * Get server version, runtime and host info (for the 关于服务端 page)
 */
export async function getServerAbout(): Promise<ServerAbout> {
  const response = await http.get('/server/about')
  return response.data
}

// --- 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json ---

export type PublicAddressSource = 'configured' | 'origin'
export type LanAddressSource = 'configured' | 'origin' | 'detected'

/**
 * 服务器二维码内容。`payload` 由服务端唯一的编码器生成，Web 端只负责把它画成
 * 二维码；两个地址都不存在时 payload 为 null。某个地址不存在时，地址与来源都是 null。
 */
export interface ConnectQrInfo {
  version: number
  payload: string | null
  public_address: string | null
  public_source: PublicAddressSource | null
  lan_address: string | null
  lan_source: LanAddressSource | null
}

/**
 * 服务器地址设置（仅管理员）。前两项是配置值，"" 表示未配置；
 * detected_lan_address 是服务端自动检测到的局域网地址，只用于提示。
 */
export interface ServerAddressSettings {
  public_address: string
  lan_address_override: string
  detected_lan_address: string | null
}

/**
 * Get the server QR code payload and the effective addresses.
 * `origin` is passed through untouched; the server decides whether to use it.
 */
export async function getConnectQr(origin: string): Promise<ConnectQrInfo> {
  const response = await http.get('/server/connect-qr', { params: { origin } })
  return response.data
}

/** Get the configured server addresses (admin only). */
export async function getServerAddressSettings(): Promise<ServerAddressSettings> {
  const response = await http.get('/server/address-settings')
  return response.data
}

/**
 * Replace both configured server addresses (admin only). An empty string clears
 * the value. Invalid input yields 422 with
 * `detail = { code: 'invalid_address', message, fields: { <field>: { code, message } } }`.
 */
export async function updateServerAddressSettings(body: {
  public_address: string
  lan_address_override: string
}): Promise<ServerAddressSettings> {
  const response = await http.put('/server/address-settings', body)
  return response.data
}
