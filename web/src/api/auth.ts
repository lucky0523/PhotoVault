import http from './http'

export interface RegisterParams {
  username: string
  password: string
}

export interface RegistrationStatus {
  allow_registration: boolean
}

/**
 * Register a new user account
 */
export async function register(params: RegisterParams) {
  const response = await http.post('/auth/register', params)
  return response.data
}

/**
 * Check if public registration is enabled
 */
export async function getRegistrationStatus(): Promise<RegistrationStatus> {
  const response = await http.get('/auth/registration-status')
  return response.data
}
