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
  /**
   * 上面两个列表中已经存在可用 PhotoVault 数据的目录。
   *
   * 选中这些目录就意味着「沿用已有库」，不需要再创建管理员账户。随列表一起返回，
   * 界面才能在用户点按钮之前就把它标出来、把按钮文字改成「完成设置」。
   * 字符串与列表里的原样一致，可直接比较。
   */
  library_paths: string[]
}

export interface WorkDirCheckResult {
  /** 规范化后的目录路径。 */
  work_dir: string
  /**
   * 该目录里已经有 PhotoVault 数据库且其中存在账号。
   *
   * 此时初始化不需要再创建管理员：直接沿用这个库，原有账号继续有效。
   */
  has_existing_library: boolean
  /** 供界面展示的说明文字。 */
  message: string
}

export interface InitSetupParams {
  /** 管理员用户名。沿用已有库时不需要。 */
  username?: string
  /** 管理员密码。沿用已有库时不需要。 */
  password?: string
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
 * 检查候选工作目录是否可用，以及里面是否已经有 PhotoVault 数据。
 *
 * 只对全新目录调用：结果决定还要不要问管理员账号，同时把「目录不可写／未授权」
 * 这类问题提前暴露在用户还在看目录选择器的时候。已经在 work-dir-options 的
 * library_paths 里的目录不必再探，那边已经给出结论。
 *
 * 副作用：目录不存在时会被创建，并写入一个探测文件再删掉——共享目录用的是
 * ACL，POSIX 权限位会给出假阴性，只有真的写一次才算准。
 */
export async function checkWorkDir(workDir: string): Promise<WorkDirCheckResult> {
  const response = await http.post('/setup/work-dir/check', { work_dir: workDir })
  return response.data
}

/**
 * 完成初始化：定下工作目录，并在需要时创建管理员账户。
 *
 * 账号与工作目录在**同一个请求**里提交，服务端要么全部建好、要么什么都不留。
 * 因此中途放弃向导不会留下半初始化状态，下次启动会重新走一遍。
 * 沿用已有库时不传账号密码，服务端不会创建新账号。
 */
export async function initSetup(params: InitSetupParams): Promise<InitSetupResult> {
  const response = await http.post('/setup/init', params)
  return response.data
}
