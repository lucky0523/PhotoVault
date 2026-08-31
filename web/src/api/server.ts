import http from './http'

export interface ServerInfo {
  lan_ips: string[]
  port: number
}

/** Storage paths and disk usage. Only returned to admin users. */
export interface ServerStorageInfo {
  storage_root: string
  database_path: string
  log_dir: string
  models_root: string
  total_gb: number
  used_gb: number
  available_gb: number
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
