import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import {
  TOKEN_EXPIRY_SKEW_MS,
  getAccessToken,
  getRefreshToken,
  isTokenUsable,
  saveTokens,
} from '@/utils/authTokens'
import { endSession } from '@/utils/session'

const API_BASE = '/api/v1'

const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
})

/**
 * 换令牌专用的实例：没有下面那对拦截器。
 *
 * 用 http 自己去发刷新请求会绕成一个圈 —— 刷新失败返回 401，响应拦截器又去刷新。
 */
const tokenClient = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
})

/**
 * 不带（也不需要）访问令牌的接口。
 *
 * 对它们跳过"过期就先刷新"这一步，否则服务器初始化向导这类未登录场景会被一次
 * 注定失败的刷新拖住。
 */
const PUBLIC_PATHS = [
  '/auth/login',
  '/auth/register',
  '/auth/refresh',
  '/auth/registration-status',
  '/setup',
  '/connection/test',
]

/**
 * 401 不该重试的接口。
 *
 * 登录和注册的 401 是"用户名密码不对"，不是"会话过期"：换令牌没有意义，页面需要
 * 原样拿到这个错误去显示。刷新接口不在此列，因为它走的是上面那个没有拦截器的
 * tokenClient，根本到不了这里。
 */
const NO_RETRY_PATHS = ['/auth/login', '/auth/register']

/**
 * 标记"这个请求已经因 401 重放过一次"，防止无限重试。
 *
 * 必须是字符串键，不能用 Symbol：重放走的是 http(config)，axios 的 mergeConfig
 * 用 Object.keys 遍历配置，Symbol 键会被静默丢掉，于是每次重放都像第一次 ——
 * 服务端持续返回 401 时就是一个打不住的死循环。
 */
const RETRIED = '_photovaultRetriedAfterRefresh'

type RetriableConfig = InternalAxiosRequestConfig & { [RETRIED]?: boolean }

function matches(url: string | undefined, paths: string[]): boolean {
  if (!url) return false
  // baseURL 之外的绝对地址也能正确判断
  const path = url.startsWith(API_BASE) ? url.slice(API_BASE.length) : url
  return paths.some((p) => path.startsWith(p))
}

/** 正在进行的刷新。并发请求共用它，避免同时甩出好几次 /auth/refresh。 */
let inflightRefresh: Promise<string> | null = null

async function requestNewTokens(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('没有可用的刷新令牌')
  }
  // 刷新令牌本身也过期了就别白跑一趟网络（服务端默认 7 天）。
  if (!isTokenUsable(refreshToken)) {
    throw new Error('刷新令牌已过期')
  }

  const { data } = await tokenClient.post('/auth/refresh', {
    refresh_token: refreshToken,
  })
  saveTokens(data.access_token, data.refresh_token)
  return data.access_token as string
}

/**
 * 用刷新令牌换一对新令牌，并返回新的访问令牌。
 *
 * 同一时刻只会有一次真正的请求在飞，后来的调用方等同一个 promise。
 *
 * @throws 刷新令牌缺失、已过期或被服务端拒绝时抛出。调用方应据此结束会话。
 */
export function refreshSession(): Promise<string> {
  if (!inflightRefresh) {
    inflightRefresh = requestNewTokens().finally(() => {
      inflightRefresh = null
    })
  }
  return inflightRefresh
}

// 请求拦截器：带上访问令牌，过期的话先换一张。
//
// 主动换而不是等 401 再换，是为了让"离开一天再回来"这种情况只走一次刷新，而不
// 是先让首屏的一批并发请求各撞一次 401。
http.interceptors.request.use(
  async (config) => {
    let token = getAccessToken()

    if (token && !matches(config.url, PUBLIC_PATHS) && !isTokenUsable(token, TOKEN_EXPIRY_SKEW_MS)) {
      try {
        token = await refreshSession()
      } catch (error) {
        // 访问令牌过期且换不回来 = 会话真的结束了，当场踢回登录页，
        // 不必再发一个注定 401 的请求。
        endSession()
        throw error
      }
    }

    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：401 时刷新令牌并重放一次，刷不出来才结束会话。
http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status
    const config = error.config as RetriableConfig | undefined

    if (status !== 401 || !config) {
      return Promise.reject(error)
    }

    if (matches(config.url, NO_RETRY_PATHS)) {
      return Promise.reject(error)
    }

    // 已经带着新令牌重放过一次还是 401：问题不在令牌新旧，别再绕圈。
    if (config[RETRIED]) {
      endSession()
      return Promise.reject(error)
    }

    let token: string
    try {
      token = await refreshSession()
    } catch {
      // 换不出新令牌 = 会话真的结束了。
      endSession()
      return Promise.reject(error)
    }

    // 重放放在 try 之外：它自己的失败要原样传出去，由它自己那一轮拦截器处理
    // （上面的 RETRIED 分支）。包进 try 会让同一次失败被处理两遍。
    config[RETRIED] = true
    config.headers.Authorization = `Bearer ${token}`
    return http(config)
  }
)

export default http
