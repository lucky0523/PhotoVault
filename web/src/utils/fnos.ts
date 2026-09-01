/**
 * 飞牛（fnOS）前端 JS SDK 适配层。
 *
 * 只做三件事，业务判断留给页面：
 *   1. 惰性地创建唯一的 TrimApp 实例，并给初始化加超时；
 *   2. 把「宿主内直调」和「独立浏览器页 + 路由授权」两条路径收敛成一个函数；
 *   3. 生成并保管授权 state，让回调结果能被确认属于本次请求。
 *
 * 本文件只在飞牛构建（__FNOS_BUILD__）里被引用。
 *
 * 参考：
 *   https://developer.fnnas.com/api/calling/
 *   https://developer.fnnas.com/api/authorization/shared-access/
 */
import type { AppAuthResult, AppBridgeResponse, PlatformConfig, TrimApp } from '@trimjs/web-app'
import type { SidebarGroup } from '@fn/micro-app-postmate'

/**
 * 应用名，必须与 packaging/fnos/manifest 的 appname 一致，否则授权会挂到别的
 * 应用名下、后端也查不到结果。
 *
 * 这里只作为兜底：页面会优先用后端 /fnos/env 返回的 app_name（那是运行时
 * TRIM_APPNAME 的真实值）。
 */
export const FNOS_APP_NAME_FALLBACK = 'photovault'

/** 授权回调页的路由路径，与 router/index.ts 中注册的路由保持一致。 */
export const FNOS_AUTH_CALLBACK_PATH = '/fnos-auth-callback'

/** 回调页通知原页面时用的消息类型，两边必须一致。 */
export const FNOS_AUTH_MESSAGE_TYPE = 'photovault:fnos-auth-result'

/** state 的存放位置。sessionStorage 而不是内存：target='_self' 时原页面会被重建。 */
const AUTH_STATE_STORAGE_KEY = 'photovault.fnos.authState'

/**
 * SDK 初始化超时。
 *
 * 非常必要而不是保险起见：SDK 的 isStandaloneWeb 判定就是 `window.parent === window`，
 * 只要页面在 iframe 里就会去和父窗口做 postmate 握手，而握手的 Promise 没有超时。
 * 如果父窗口不是飞牛宿主（例如被别的页面 iframe 引用），initPromise 永远不 settle，
 * 所有 SDK 方法都会无限等待，页面表现为一直转圈。
 */
const SDK_READY_TIMEOUT_MS = 5000

/** 目录选择器默认展示的侧边栏分组。 */
export const DEFAULT_SIDEBAR_GROUPS: SidebarGroup[] = ['myFiles', 'otherShare', 'favorites']

/**
 * 把值转成纯数据，去掉 Vue 的响应式包装。
 *
 * 这不是洁癖，是必须的：宿主内直调时 SDK 通过 postMessage 把参数发给父窗口，
 * 而 postMessage 用的结构化克隆算法克隆不了 Proxy。直接把 ref/reactive 的值
 * （例如 el-select 绑定的 sidebarGroups.value，它是个 reactive 数组 Proxy）
 * 传进 SDK，浏览器会抛：
 *
 *   Failed to execute 'postMessage' on 'Window':
 *   [object Object] could not be cloned.
 *
 * 而且这个错来自 SDK 内部的 penpal 桥，堆栈里看不到自己的代码，很难联想到是
 * 响应式代理导致的。所以在「进 SDK 之前」这一个入口统一洗一遍。
 *
 * 用 JSON 往返而不是 toRaw：toRaw 只解一层，而 JSON 往返既能剥掉任意层级的
 * 代理，又能顺手保证结果一定是可克隆的。SDK 的参数都是字符串/布尔/字符串数组
 * 这类纯 JSON 数据，往返没有信息损失。
 */
function toPlain<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

// ---------------------------------------------------------------------------
// SDK 实例
// ---------------------------------------------------------------------------

let sdkPromise: Promise<TrimApp> | null = null

/**
 * 取得 TrimApp 单例。
 *
 * 动态 import 而不是顶层 import：这样 SDK 会被拆成独立 chunk，跟着调用方一起
 * 从非飞牛构建里消失，而不是被并入主 bundle。
 */
export function getSdk(): Promise<TrimApp> {
  if (!sdkPromise) {
    sdkPromise = import('@trimjs/web-app').then((mod) => new mod.TrimApp())
  }
  return sdkPromise
}

export class FnosSdkTimeoutError extends Error {
  constructor(ms: number) {
    super(`飞牛宿主在 ${ms}ms 内没有完成握手，当前页面可能不在飞牛宿主环境中`)
    this.name = 'FnosSdkTimeoutError'
  }
}

/**
 * 等待 SDK 初始化完成，超时抛 FnosSdkTimeoutError。
 */
export async function getReadySdk(timeoutMs = SDK_READY_TIMEOUT_MS): Promise<TrimApp> {
  const sdk = await getSdk()

  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await Promise.race([
      sdk.ready(),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new FnosSdkTimeoutError(timeoutMs)), timeoutMs)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }

  return sdk
}

// ---------------------------------------------------------------------------
// 运行环境
// ---------------------------------------------------------------------------

export interface FnosHostEnv {
  /** SDK 是否完成初始化（false 表示握手超时或抛错） */
  ready: boolean
  /** 是否 Web 环境；移动端 App 内嵌页为 false */
  isWeb: boolean
  /** 是否独立浏览器页面；true 表示没有运行在宿主环境中 */
  isStandaloneWeb: boolean
  /** 是否处在 iframe 中（等价于 isStandaloneWeb 取反，单独列出便于对照排查） */
  inIframe: boolean
  /** 宿主平台配置，读取失败时为 null */
  platformConfig: PlatformConfig | null
  /** 从 document.referrer 推出的飞牛 origin，只有在宿主 iframe 里才有值 */
  hostOrigin: string
  /** ready 为 false 或读取平台配置失败时的原因 */
  error: string
}

/**
 * 探测宿主环境。
 *
 * 任何一步失败都不抛，而是把原因塞进返回值：这个页面的用途就是「看清当前到底
 * 处在什么环境」，抛异常会让页面只剩一个红条，反而看不到 isStandaloneWeb 之类
 * 的关键信息。
 */
export async function probeHostEnv(): Promise<FnosHostEnv> {
  const inIframe = typeof window !== 'undefined' && window.parent !== window

  const env: FnosHostEnv = {
    ready: false,
    isWeb: true,
    isStandaloneWeb: !inIframe,
    inIframe,
    platformConfig: null,
    hostOrigin: '',
    error: '',
  }

  let sdk: TrimApp
  try {
    sdk = await getReadySdk()
    env.ready = true
  } catch (e) {
    env.error = toErrorMessage(e)
    return env
  }

  env.isWeb = sdk.isWeb
  env.isStandaloneWeb = sdk.isStandaloneWeb

  // 在宿主 iframe 里时，document.referrer 就是飞牛页面，顺手把 origin 记下来。
  // 之后同一浏览器以独立页面打开本应用（同源，共享 localStorage）就能自动填上
  // 飞牛系统地址，省掉猜端口这一步。
  if (inIframe && document.referrer) {
    try {
      const origin = new URL(document.referrer).origin
      env.hostOrigin = origin
      rememberHostOrigin(origin)
    } catch {
      // referrer 不是合法 URL，忽略
    }
  }

  // 独立浏览器页面没有宿主，getPlatformConfig 注定失败，不必去撞一次。
  if (!sdk.isStandaloneWeb) {
    try {
      env.platformConfig = await sdk.getPlatformConfig()
    } catch (e) {
      env.error = `读取宿主平台配置失败：${toErrorMessage(e)}`
    }
  }

  return env
}

// ---------------------------------------------------------------------------
// 飞牛系统地址（路由授权用）
// ---------------------------------------------------------------------------
// 路由授权要打开的 /app-auth/* 是**飞牛系统 UI 的路由**，不是本应用的路由，
// 必须落在飞牛的 origin 上。
//
// SDK 自己推导基址的逻辑是（dist/index.js 的 getAppAuthBaseUrl）：
//   1. 在 iframe 里  -> document.referrer 的 origin
//   2. 否则          -> window.location.origin
//
// 第 2 条对本应用是错的：PhotoVault 通过 app/ui/config 以 type=iframe 挂在自己
// 的端口上（http://<nas>:8000），不是走统一网关的 /app/photovault/，所以独立页面
// 下 location.origin 永远是我们自己，拼出来的 /app-auth/pick-shared-file 会被
// FastAPI 的 SPA catch-all 兜成 index.html，表现为**打开一个空白页**。
//
// SDK 没有导出可覆盖基址的入口（包根只导出 TrimApp），所以独立页面这条路只能
// 自己拼 URL，基址由使用者提供。路由名与查询参数按 SDK 的实现对齐。
// ---------------------------------------------------------------------------

/** 飞牛 Web 管理界面的默认端口。 */
const FNOS_DEFAULT_PORT = 5666

const HOST_ORIGIN_STORAGE_KEY = 'photovault.fnos.hostOrigin'

/** method -> /app-auth/<route>，取自 SDK 的 appAuthMethodRouteMap。 */
const APP_AUTH_ROUTES = {
  pickSharedFile: 'pick-shared-file',
  authorizeSharedFile: 'authorize-shared-file',
} as const

export type SharedAuthMethod = keyof typeof APP_AUTH_ROUTES

/**
 * 记住飞牛的 origin。
 *
 * 在宿主 iframe 里时 document.referrer 就是飞牛页面，可以白捡一个准确值存下来；
 * 之后同一浏览器以独立页面打开本应用时（同源，共享 localStorage）就能直接用，
 * 不必让使用者去猜端口。
 */
export function rememberHostOrigin(origin: string): void {
  try {
    if (origin) localStorage.setItem(HOST_ORIGIN_STORAGE_KEY, origin)
  } catch {
    // 隐私模式下不可写，只是少了一个默认值，不影响手填
  }
}

/**
 * 猜一个飞牛系统地址，作为输入框的默认值。
 *
 * 顺序：上次记住的 -> 宿主 iframe 的 referrer -> 当前主机名 + 5666。
 * 最后那个只是常见默认端口，使用者可以在页面上改。
 */
export function guessHostOrigin(): string {
  try {
    const saved = localStorage.getItem(HOST_ORIGIN_STORAGE_KEY)
    if (saved) return saved
  } catch {
    // 忽略，继续往下猜
  }

  if (typeof window !== 'undefined' && window.parent !== window && document.referrer) {
    try {
      return new URL(document.referrer).origin
    } catch {
      // referrer 不是合法 URL，继续往下猜
    }
  }

  return `${window.location.protocol}//${window.location.hostname}:${FNOS_DEFAULT_PORT}`
}

/**
 * 拼出飞牛路由授权页的 URL。
 *
 * 参数与 SDK 的 buildAppAuthUrl 保持一致：appName / redirectUri / state 通用，
 * pickSharedFile 额外带 sidebarGroup（逗号分隔），authorizeSharedFile 额外带 path。
 */
export function buildSharedAuthUrl(
  method: SharedAuthMethod,
  hostOrigin: string,
  params: {
    appName: string
    redirectUri?: string
    state?: string
    sidebarGroup?: SidebarGroup[]
    path?: string
  }
): string {
  let url: URL
  try {
    url = new URL(hostOrigin)
  } catch {
    throw new Error(`飞牛系统地址不是合法的 URL：${hostOrigin || '(空)'}`)
  }

  url.pathname = `/app-auth/${APP_AUTH_ROUTES[method]}`
  url.search = ''
  url.searchParams.set('appName', params.appName)
  if (params.redirectUri) url.searchParams.set('redirectUri', params.redirectUri)
  if (params.state) url.searchParams.set('state', params.state)

  if (method === 'pickSharedFile') {
    if (params.sidebarGroup?.length) {
      url.searchParams.set('sidebarGroup', params.sidebarGroup.join(','))
    }
  } else if (params.path) {
    url.searchParams.set('path', params.path)
  }

  return url.toString()
}

/** 打开授权页。_self 直接跳转，_blank 开子窗口并检测是否被拦截。 */
function openAuthUrl(url: string, target: '_blank' | '_self'): void {
  if (target === '_self') {
    window.location.assign(url)
    return
  }

  const win = window.open(url, '_blank', 'width=750,height=630')
  // 自己检测拦截而不是交给 SDK：SDK 的 openURL 拿不到窗口也会静默返回，
  // 表现为「点了没反应」，和授权失败混在一起分不清。
  if (!win) {
    throw new Error('浏览器拦截了授权窗口，请允许本站弹窗后重试，或改用 _self 方式')
  }
}

// ---------------------------------------------------------------------------
// 授权 state
// ---------------------------------------------------------------------------

/**
 * 生成并保存一个不可预测的 state。
 *
 * state 不参与授权本身，作用是让回调结果能被确认来自本次请求；文档也建议对安全
 * 敏感的流程使用不可预测值，并在导航前存下来。
 */
export function createAuthState(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const state = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  try {
    sessionStorage.setItem(AUTH_STATE_STORAGE_KEY, state)
  } catch {
    // 隐私模式下 sessionStorage 可能不可写。state 仍然会随 URL 带出去并回传，
    // 只是没法在 target='_self' 重建页面后校验，不影响授权本身。
  }
  return state
}

/** 读取当前保存的 state；没有则返回空字符串。 */
export function readAuthState(): string {
  try {
    return sessionStorage.getItem(AUTH_STATE_STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

/** 用完即弃，避免同一个 state 被第二次回调复用。 */
export function clearAuthState(): void {
  try {
    sessionStorage.removeItem(AUTH_STATE_STORAGE_KEY)
  } catch {
    // 同上，忽略
  }
}

// ---------------------------------------------------------------------------
// 目录授权
// ---------------------------------------------------------------------------

/** 一次授权动作的归一化结果。 */
export interface SharedAuthOutcome {
  /** 走的哪条路径 */
  mode: 'bridge' | 'route'
  /** 宿主内直调返回的业务码；路由授权没有该字段 */
  code?: number
  /** 宿主内直调返回的消息 */
  msg?: string
  /** 本次授权涉及的目录路径（宿主内直调时由 SDK 返回） */
  paths: string[]
  /** 路由授权时实际打开的 URL，便于排查 redirectUri 是否符合预期 */
  openedUrl?: string
  /** 原始返回值，页面会原样展示，避免适配层把有用信息吃掉 */
  raw: unknown
}

export interface SharedAuthOptions {
  /** 后端 /fnos/env 返回的 app_name */
  appName: string
  /** 飞牛系统地址（如 http://10.211.55.5:5666），仅路由授权用到 */
  hostOrigin: string
  /** 授权回调页地址，仅路由授权用到 */
  redirectUri: string
  /** 目录选择器左侧分组 */
  sidebarGroups?: SidebarGroup[]
  /** 打开授权页的方式，仅路由授权用到 */
  target?: '_blank' | '_self'
}

/**
 * SDK 对 pickSharedFile / authorizeSharedFile 的返回类型并不一致：
 * pickSharedFile 是 AppBridgeResponse<string[]>，authorizeSharedFile 是
 * AppBridgeResponse<boolean>，而官方文档把两者都写成了 string[]。
 * 这里按实际值兼容两种形态，别让页面去猜。
 */
function normalizePaths(data: unknown, fallbackPath?: string): string[] {
  if (Array.isArray(data)) {
    return data.filter((p): p is string => typeof p === 'string')
  }
  if (data === true && fallbackPath) {
    return [fallbackPath]
  }
  return []
}

function toOutcome(
  response: AppBridgeResponse<unknown> | undefined,
  fallbackPath?: string
): SharedAuthOutcome {
  return {
    mode: 'bridge',
    code: response?.code,
    msg: response?.msg,
    paths: normalizePaths(response?.data, fallbackPath),
    raw: response,
  }
}

/**
 * 打开目录选择框，让管理员选择要授权给应用的目录（pickSharedFile）。
 *
 * 宿主内直调与独立浏览器页面走两条不同路径，由 isStandaloneWeb 决定；
 * 独立页面下必须由用户点击触发，否则新窗口会被浏览器拦截。
 */
export async function pickSharedFolder(options: SharedAuthOptions): Promise<SharedAuthOutcome> {
  const sdk = await getReadySdk()
  const sidebarGroup = toPlain(options.sidebarGroups ?? DEFAULT_SIDEBAR_GROUPS)

  if (!sdk.isStandaloneWeb) {
    // 参数必须是纯数据，见 toPlain 的说明。
    const result = await sdk.pickSharedFile(
      toPlain({
        title: '选择授权给 PhotoVault 的目录',
        okText: '确认授权',
        sidebarGroup,
      })
    )
    return toOutcome(result)
  }

  const openedUrl = buildSharedAuthUrl('pickSharedFile', options.hostOrigin, {
    appName: options.appName,
    sidebarGroup,
    redirectUri: options.redirectUri,
    state: createAuthState(),
  })
  openAuthUrl(openedUrl, options.target ?? '_blank')
  return { mode: 'route', paths: [], openedUrl, raw: { openedUrl } }
}

/**
 * 就一个已知目录重新申请授权（authorizeSharedFile）。
 */
export async function authorizeSharedFolder(
  path: string,
  options: SharedAuthOptions
): Promise<SharedAuthOutcome> {
  const sdk = await getReadySdk()

  if (!sdk.isStandaloneWeb) {
    const result = await sdk.authorizeSharedFile(path)
    return toOutcome(result, path)
  }

  const openedUrl = buildSharedAuthUrl('authorizeSharedFile', options.hostOrigin, {
    appName: options.appName,
    path,
    redirectUri: options.redirectUri,
    state: createAuthState(),
  })
  openAuthUrl(openedUrl, options.target ?? '_blank')
  return { mode: 'route', paths: [], openedUrl, raw: { openedUrl } }
}

// ---------------------------------------------------------------------------
// 回调解析
// ---------------------------------------------------------------------------

/** 解析当前 URL 里的授权回调结果。 */
export async function parseAuthCallback(url?: string): Promise<AppAuthResult> {
  const sdk = await getSdk()
  // parseAppAuthCallback 是纯函数，不碰宿主桥，所以不需要等 ready()：
  // 回调页往往就是独立页面，等握手只会白等一个超时。
  return sdk.parseAppAuthCallback(url ?? window.location.href)
}

/** 把任意异常转成可展示的一行文字。 */
export function toErrorMessage(e: unknown): string {
  if (e instanceof Error) return e.message
  if (typeof e === 'string') return e
  try {
    return JSON.stringify(e)
  } catch {
    return String(e)
  }
}
