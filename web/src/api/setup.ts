import http from './http'

export interface SetupStatus {
  initialized: boolean
}

export interface InitSetupParams {
  username: string
  password: string
}

/**
 * Get system initialization status
 */
export async function getSetupStatus(): Promise<SetupStatus> {
  const response = await http.get('/setup/status')
  return response.data
}

/**
 * Initialize the system by creating the admin account
 */
export async function initSetup(params: InitSetupParams): Promise<void> {
  await http.post('/setup/init', params)
}
