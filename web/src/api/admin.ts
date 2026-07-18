import http from './http'

export interface UserInfo {
  id: number
  username: string
  is_admin: boolean
  created_at: string
}

export interface CreateUserParams {
  username: string
  password: string
  is_admin: boolean
}

/**
 * List all users (admin only)
 */
export async function listUsers(): Promise<UserInfo[]> {
  const response = await http.get('/admin/users')
  return response.data
}

/**
 * Create a new user (admin only)
 */
export async function createUser(params: CreateUserParams): Promise<UserInfo> {
  const response = await http.post('/admin/users', params)
  return response.data
}

/**
 * Delete a user by ID (admin only)
 */
export async function deleteUser(userId: number): Promise<void> {
  await http.delete(`/admin/users/${userId}`)
}

export interface ClearPurgedRecordsResponse {
  success: boolean
  count: number
}

/**
 * Remove a user's already-purged file records and their associated analysis data (admin only)
 */
export async function clearPurgedRecords(userId: number): Promise<ClearPurgedRecordsResponse> {
  const response = await http.delete(`/admin/users/${userId}/purged-records`)
  return response.data
}

/**
 * Change a user's password (admin only)
 */
export async function changePassword(userId: number, newPassword: string): Promise<void> {
  await http.put(`/admin/users/${userId}/password`, { new_password: newPassword })
}
