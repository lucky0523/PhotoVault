import http from './http'

export interface ClientVersionInfo {
  id: number
  platform: 'android'
  package_name: string
  version_name: string
  version_code: number
  original_filename: string
  file_size: number
  sha256: string
  release_notes?: string | null
  created_at: string
  is_latest: boolean
  download_url?: string | null
}

export async function listClientVersions(): Promise<ClientVersionInfo[]> {
  const response = await http.get('/admin/client-versions')
  return response.data
}

export async function uploadClientVersion(
  file: File,
  releaseNotes?: string
): Promise<ClientVersionInfo> {
  const formData = new FormData()
  formData.append('file', file)
  if (releaseNotes?.trim()) {
    formData.append('release_notes', releaseNotes.trim())
  }
  const response = await http.post('/admin/client-versions', formData, {
    timeout: 10 * 60 * 1000,
  })
  return response.data
}

export async function deleteClientVersion(versionId: number): Promise<void> {
  await http.delete(`/admin/client-versions/${versionId}`)
}

export async function getLatestAndroidClient(): Promise<ClientVersionInfo> {
  const response = await http.get('/clients/android/latest')
  return response.data
}
