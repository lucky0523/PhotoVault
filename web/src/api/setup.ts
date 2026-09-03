import http from './http'

export interface SetupStatus {
  initialized: boolean
  /**
   * 初始化时是否需要让管理员选定工作目录。
   *
   * 只有飞牛应用会返回 true：那里的照片必须放在平台授予过 ACL 的目录里，而授权只能
   * 在已经跑起来的应用界面里完成，所以服务端在选定之前刻意不创建数据库。
   * Docker/开发环境的存储位置由配置决定，这里恒为 false，初始化仍是一步式的。
   */
  needs_work_dir: boolean
}

export interface WorkDirOptions {
  /** 平台托管的共享文件夹，恒可写，作为兜底选项。 */
  default_path: string
  /** 管理员已通过飞牛目录授权开放给本应用的目录。 */
  authorized_paths: string[]
  /** 是否成功读到了授权列表。 */
  gateway_available: boolean
  /** 读不到时的原因，用于区分「还没授权任何目录」和「连不上飞牛」。 */
  gateway_reason: string
  /**
   * 上次使用的工作目录（卸载重装、或授权被收回时保留下来的）。
   *
   * 重新授权这个目录就能找回原有照片和数据库。之所以要显示：选择器只列已授权目录，
   * 授权被收回后它不会出现在列表里，而界面上也没有手填路径的地方。
   */
  previous_path: string
}

export interface InitSetupParams {
  username: string
  password: string
  /** 工作目录绝对路径。needs_work_dir 为 true 时必填。 */
  work_dir?: string
}

export interface InitSetupResult {
  success: boolean
  username: string
  work_dir: string
  /**
   * 选定目录里已存在 PhotoVault 数据，服务端直接沿用了它，本次填写的账号未创建。
   * 用于重装找回：管理员应当用原有账号登录。
   */
  adopted: boolean
  message: string
}

/**
 * Get system initialization status
 */
export async function getSetupStatus(): Promise<SetupStatus> {
  const response = await http.get('/setup/status')
  return response.data
}

/**
 * 读取可作为工作目录的候选目录。
 *
 * 单独一个初始化专用接口，而不是复用 /fnos/shared-folders：后者需要管理员登录，
 * 而这一步恰恰还没有账号。它只在初始化未完成时可用，完成后返回 403。
 */
export async function getWorkDirOptions(): Promise<WorkDirOptions> {
  const response = await http.get('/setup/work-dir-options')
  return response.data
}

/**
 * 完成初始化：创建管理员账户，必要时同时定下工作目录。
 *
 * 账号与工作目录在**同一个请求**里提交，服务端要么全部建好、要么什么都不留。
 * 因此中途放弃向导不会留下半初始化状态，下次启动会重新走一遍。
 */
export async function initSetup(params: InitSetupParams): Promise<InitSetupResult> {
  const response = await http.post('/setup/init', params)
  return response.data
}
