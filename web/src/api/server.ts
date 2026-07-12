import http from './http'

export interface ServerInfo {
  lan_ips: string[]
  port: number
}

/**
 * Get server LAN IP addresses and port (for QR code generation)
 */
export async function getServerInfo(): Promise<ServerInfo> {
  const response = await http.get('/server/info')
  return response.data
}
