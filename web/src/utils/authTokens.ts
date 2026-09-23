/**
 * 登录凭证的唯一存取点。
 *
 * 在这之前，http 拦截器、pinia store 和登录页各自直接读写 localStorage，于是
 * 出现过一类很难查的故障：401 拦截器把存储清空了，但 store 里的 ref 还留着旧
 * 令牌，`isAuthenticated` 仍为 true，导航守卫又把跳向 /login 的导航弹回
 * /photos —— 令牌过期后页面既登录不了也用不了任何接口。
 *
 * 所有读写收敛到这里，并在变更时通知订阅者（store 借此把 ref 同步成存储的镜
 * 像），那一类不一致就不可能再出现。
 *
 * 持久化位置有两种，由"记住登录状态"决定：
 * - localStorage：跨浏览器重启保留（默认）
 * - sessionStorage：关掉浏览器即失效
 * 读取时 sessionStorage 优先，所以"只记住本次会话"的登录不会被上一次遗留在
 * localStorage 里的令牌盖掉。
 */

export interface StoredUserInfo {
  id: number
  username: string
  is_admin: boolean
}

const ACCESS_TOKEN_KEY = 'access_token'
const REFRESH_TOKEN_KEY = 'refresh_token'
const USER_INFO_KEY = 'user_info'

/** 标记"凭证只在本次浏览器会话有效"。放在 sessionStorage 里，语义自带过期。 */
const SESSION_ONLY_KEY = 'photovault.session_only'

/**
 * 令牌提前多久算作过期。
 *
 * 用于在真正过期前主动换新，避开两件事：客户端与服务端的时钟偏差，以及请求在
 * 路上耗掉的时间（发出时还有效，到达服务端时已过期）。
 */
export const TOKEN_EXPIRY_SKEW_MS = 30_000

type Listener = () => void

const listeners = new Set<Listener>()

/** 隐私模式下连访问 storage 都会抛，所以每一次接触都要兜住。 */
function storage(kind: 'local' | 'session'): Storage | null {
  try {
    return kind === 'local' ? window.localStorage : window.sessionStorage
  } catch {
    return null
  }
}

function readFrom(store: Storage | null, key: string): string | null {
  if (!store) return null
  try {
    return store.getItem(key)
  } catch {
    return null
  }
}

function writeTo(store: Storage | null, key: string, value: string): void {
  if (!store) return
  try {
    store.setItem(key, value)
  } catch {
    // 写不进去（隐私模式、配额满）只意味着凭证活不过这次刷新，
    // 不该让登录本身失败。
  }
}

function removeFrom(store: Storage | null, key: string): void {
  if (!store) return
  try {
    store.removeItem(key)
  } catch {
    // 同上
  }
}

function isSessionOnly(): boolean {
  return readFrom(storage('session'), SESSION_ONLY_KEY) === '1'
}

/** 当前该往哪儿写。 */
function writeTarget(): Storage | null {
  return isSessionOnly() ? storage('session') : storage('local')
}

function read(key: string): string | null {
  const fromSession = readFrom(storage('session'), key)
  if (fromSession !== null) return fromSession
  return readFrom(storage('local'), key)
}

function notify(): void {
  for (const listener of [...listeners]) {
    try {
      listener()
    } catch {
      // 一个订阅者出错不该拖累其它订阅者，也不该让清理凭证的调用方失败。
    }
  }
}

/**
 * 订阅凭证变更。
 *
 * @returns 取消订阅的函数。
 */
export function subscribeCredentials(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

// 另一个标签页改了 localStorage 也要通知下去：否则在一个标签页退出登录后，另一个
// 标签页会继续以为自己还登录着，直到下一次请求撞上 401。
try {
  window.addEventListener?.('storage', (event) => {
    if (
      event.key === null || // storage.clear()
      event.key === ACCESS_TOKEN_KEY ||
      event.key === REFRESH_TOKEN_KEY ||
      event.key === USER_INFO_KEY
    ) {
      notify()
    }
  })
} catch {
  // 没有 window（SSR、测试环境）时跳过即可。
}

export function getAccessToken(): string | null {
  return read(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return read(REFRESH_TOKEN_KEY)
}

export function getUserInfo(): StoredUserInfo | null {
  const raw = read(USER_INFO_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as StoredUserInfo) : null
  } catch {
    return null
  }
}

/**
 * 选择凭证的持久化位置。必须在 {@link saveTokens} 之前调用。
 *
 * @param sessionOnly true 表示只存活到浏览器关闭。
 */
export function setSessionOnly(sessionOnly: boolean): void {
  const session = storage('session')
  if (sessionOnly) {
    writeTo(session, SESSION_ONLY_KEY, '1')
  } else {
    removeFrom(session, SESSION_ONLY_KEY)
  }
}

export function saveTokens(accessToken: string, refreshToken: string): void {
  const target = writeTarget()
  writeTo(target, ACCESS_TOKEN_KEY, accessToken)
  writeTo(target, REFRESH_TOKEN_KEY, refreshToken)
  notify()
}

export function saveUserInfo(info: StoredUserInfo): void {
  writeTo(writeTarget(), USER_INFO_KEY, JSON.stringify(info))
  notify()
}

/** 清空两处存储里的全部凭证，并恢复默认的持久化位置。 */
export function clearCredentials(): void {
  for (const kind of ['local', 'session'] as const) {
    const store = storage(kind)
    removeFrom(store, ACCESS_TOKEN_KEY)
    removeFrom(store, REFRESH_TOKEN_KEY)
    removeFrom(store, USER_INFO_KEY)
  }
  removeFrom(storage('session'), SESSION_ONLY_KEY)
  notify()
}

/**
 * 解出 JWT 的载荷，失败返回 null。
 *
 * 只做 base64url 解码，不验签 —— 签名由服务端校验，这里解载荷只为了本地调度
 * （要不要提前换令牌）和显示用户名。
 */
function decodePayload(token: string): Record<string, any> | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    // atob 产出的是逐字节的 "binary string"。直接 JSON.parse 会把 UTF-8 多字节
    // 字符拆成乱码（中文用户名就是这样变成"é"的），所以先还原成字节再按 UTF-8 解。
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    const payload = JSON.parse(new TextDecoder().decode(bytes))
    return payload && typeof payload === 'object' ? payload : null
  } catch {
    return null
  }
}

/** 读出 JWT 的过期时刻（毫秒时间戳），解不出来时返回 null。 */
export function getTokenExpiry(token: string | null): number | null {
  if (!token) return null
  const payload = decodePayload(token)
  if (!payload) return null
  return typeof payload.exp === 'number' ? payload.exp * 1000 : null
}

/**
 * 令牌现在是否还能用。
 *
 * @param skewMs 提前量：距过期不足这么久也算不可用。
 *
 * 解不出 exp 的令牌按"可用"处理，由服务端去裁决 —— 本地解析失败不构成把用户
 * 踢下线的理由。
 */
export function isTokenUsable(token: string | null, skewMs = 0): boolean {
  if (!token) return false
  const expiry = getTokenExpiry(token)
  if (expiry === null) return true
  return Date.now() + skewMs < expiry
}

/**
 * 从访问令牌里取出当前用户。
 *
 * @param fallbackUsername 令牌解不开时用的用户名。
 */
export function decodeUserFromToken(
  token: string,
  fallbackUsername: string
): StoredUserInfo {
  const payload = decodePayload(token)
  if (!payload) {
    return { id: 0, username: fallbackUsername, is_admin: false }
  }
  return {
    id: payload.user_id ?? payload.sub ?? 0,
    username: payload.username || fallbackUsername,
    is_admin: payload.is_admin || false,
  }
}
