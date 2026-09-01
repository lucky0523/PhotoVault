/**
 * 飞牛（fnOS）开放能力相关的后端接口。
 *
 * 目录授权分两半，缺一半就测不完整：
 *   - 申请授权：前端 JS SDK（pickSharedFile / authorizeSharedFile），见 utils/fnos.ts
 *   - 查询与删除：后端 API（trim.file.getSharedAccessibleFolders /
 *     trim.file.delSharedAccessibleFolder）
 *
 * 后端 API 只能由应用服务端通过 Unix Socket
 * /var/run/trim_open_gateway_apiscope.socket 调用，token 也只存在于服务端进程的
 * TRIM_API_TOKEN 环境变量里，所以浏览器不能直连，必须走本文件这层自家后端代理
 * （server/app/api/fnos.py）。
 *
 * 本文件只在飞牛构建（__FNOS_BUILD__）里被引用。
 *
 * 参考：https://developer.fnnas.com/api/authorization/shared-access/
 */
import http from './http'

/** 运行环境探测结果，用来判断「测不通」是环境问题还是授权问题。 */
export interface FnosEnvInfo {
  /** 后端进程是否处在飞牛应用运行时中（socket 与 token 都就绪） */
  available: boolean
  /** 不可用时的原因说明，可用时为空字符串 */
  reason: string
  /** 调用后端 API 时使用的 appName */
  app_name: string
  /** 开放网关 Unix Socket 路径 */
  socket_path: string
  /** socket 文件是否存在 */
  socket_exists: boolean
  /** 服务端进程是否拿到了 TRIM_API_TOKEN（只报告有无，不返回内容） */
  token_present: boolean
}

/** trim.file.getSharedAccessibleFolders 的结果。 */
export interface FnosSharedFolders {
  paths: string[]
}

/**
 * 探测后端所处的飞牛运行环境。
 *
 * 不会真的调用开放网关，只检查 socket 与 token，所以在非飞牛环境下也能安全调用。
 */
export async function getFnosEnv(): Promise<FnosEnvInfo> {
  const response = await http.get('/fnos/env')
  return response.data
}

/**
 * 查询管理员授权给本应用的共享目录（trim.file.getSharedAccessibleFolders）。
 */
export async function getFnosSharedFolders(): Promise<FnosSharedFolders> {
  const response = await http.get('/fnos/shared-folders')
  return response.data
}

/**
 * 删除一条共享目录授权（trim.file.delSharedAccessibleFolder）。
 *
 * 路径通过 query 传递而不是请求体：DELETE 带 body 在部分代理上会被丢掉。
 */
export async function deleteFnosSharedFolder(path: string): Promise<{ suc: boolean }> {
  const response = await http.delete('/fnos/shared-folders', { params: { path } })
  return response.data
}
