import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http, { refreshSession } from '@/api/http'
import { register as apiRegister } from '@/api/auth'
import {
  TOKEN_EXPIRY_SKEW_MS,
  clearCredentials,
  decodeUserFromToken,
  getAccessToken,
  getRefreshToken,
  getUserInfo,
  isTokenUsable,
  saveTokens,
  saveUserInfo,
  setSessionOnly,
  subscribeCredentials,
  type StoredUserInfo,
} from '@/utils/authTokens'
import { endSession } from '@/utils/session'

type UserInfo = StoredUserInfo

/** 会话巡检间隔。够快地发现过期，又不至于让空闲标签页一直忙。 */
const SESSION_CHECK_INTERVAL_MS = 60_000

export const useAuthStore = defineStore('auth', () => {
  // State
  //
  // 这三个 ref 是 utils/authTokens 里存储的镜像，不是第二份真相。下面的订阅保证
  // 任何一方（登录、刷新、401 拦截器、另一个标签页）改动凭证时它们都跟着变。
  const accessToken = ref<string | null>(getAccessToken())
  const refreshToken = ref<string | null>(getRefreshToken())
  const userInfo = ref<UserInfo | null>(readUserInfo())

  /**
   * 过期判断的时间基准。
   *
   * computed 只在依赖变化时重算，而"是否过期"依赖的是当前时刻 —— 不把时刻做成
   * 响应式依赖的话，`isAuthenticated` 会一直缓存着"还没过期"这个旧结论。巡检每
   * 次推进它，缓存随之失效。
   */
  const checkedAt = ref(Date.now())

  /**
   * 读当前用户，缺了就从访问令牌里解。
   *
   * 访问令牌本身带着 username 和 is_admin，所以 user_info 丢了（旧版本残留、写入
   * 时存储不可用）不该让界面退化成"无名的普通用户"—— 那会让管理员菜单凭空消失。
   */
  function readUserInfo(): UserInfo | null {
    const stored = getUserInfo()
    if (stored) return stored
    const token = getAccessToken()
    return token ? decodeUserFromToken(token, '') : null
  }

  function syncFromStorage(): void {
    accessToken.value = getAccessToken()
    refreshToken.value = getRefreshToken()
    userInfo.value = readUserInfo()
  }

  subscribeCredentials(syncFromStorage)

  // Getters
  //
  // 会话活着的判断是"任一令牌仍然有效"：访问令牌过期但刷新令牌还在时，会话依然
  // 可用，http 层会自动换新。之前这里只看令牌是否存在，于是过期后守卫仍然认为
  // 已登录，把跳向登录页的导航弹回主页。
  const isAuthenticated = computed(() => {
    void checkedAt.value
    return isTokenUsable(accessToken.value) || isTokenUsable(refreshToken.value)
  })
  const isAdmin = computed(() => userInfo.value?.is_admin ?? false)
  const username = computed(() => userInfo.value?.username ?? '')

  function persistSession(
    access: string,
    refresh: string,
    usernameInput: string
  ): void {
    saveTokens(access, refresh)
    saveUserInfo(decodeUserFromToken(access, usernameInput))
    checkedAt.value = Date.now()
  }

  // Actions

  /**
   * 用户名密码登录。
   *
   * @param remember false 时凭证只写进 sessionStorage，关掉浏览器即失效。
   */
  async function login(
    usernameInput: string,
    password: string,
    remember = true
  ) {
    const response = await http.post('/auth/login', {
      username: usernameInput,
      password,
    })

    const { access_token, refresh_token } = response.data

    // 先清掉上一次的凭证，再决定这一次存哪儿：否则残留在 localStorage 里的旧令
    // 牌会在本次会话的令牌被清掉后"复活"。
    clearCredentials()
    setSessionOnly(!remember)
    persistSession(access_token, refresh_token, usernameInput)
  }

  async function register(usernameInput: string, password: string) {
    const data = await apiRegister({ username: usernameInput, password })
    const { access_token, refresh_token } = data

    clearCredentials()
    setSessionOnly(false)
    persistSession(access_token, refresh_token, usernameInput)
  }

  function logout() {
    endSession({ redirect: false })
  }

  /**
   * 本地检查会话是否还活着，两个令牌都过期就清掉凭证。
   *
   * 只清不跳转：导航守卫也会调用它，在守卫里发起 router.push 会把当前这次导航打
   * 断掉。需要跳转的场合用 {@link enforceSession}。
   *
   * 不发请求，所以可以放心地定时调用。
   *
   * @returns 会话是否仍然可用。
   */
  function checkSession(): boolean {
    checkedAt.value = Date.now()
    // 以存储为准重新取一遍：凭证也可能被另一个标签页或未经通知的路径改掉。
    syncFromStorage()

    if (!getAccessToken() && !getRefreshToken()) return false
    if (isAuthenticated.value) return true

    clearCredentials()
    return false
  }

  /**
   * 巡检会话：过期就清掉凭证并回到登录页。
   *
   * 挂着不动的标签页靠它被踢出去 —— 那种标签页可能很久都不发请求，等不到 401。
   */
  function enforceSession(): void {
    const hadCredentials = !!getAccessToken() || !!getRefreshToken()
    if (!checkSession() && hadCredentials) {
      endSession()
    }
  }

  /**
   * 确保接下来的请求手里有一张有效的访问令牌，必要时先换新。
   *
   * 给导航守卫用：进入页面前把令牌换好，页面首屏的一批请求就不会先各撞一次 401。
   * 失败时只清凭证、不跳转，跳转交给守卫的 next()。
   *
   * @returns 会话是否可用。
   */
  async function ensureValidSession(): Promise<boolean> {
    if (!checkSession()) return false
    if (isTokenUsable(getAccessToken(), TOKEN_EXPIRY_SKEW_MS)) return true

    try {
      await refreshSession()
      checkedAt.value = Date.now()
      return true
    } catch {
      clearCredentials()
      return false
    }
  }

  // 定时巡检 + 回到前台时补一次。
  //
  // 只靠定时器不够：浏览器会给后台标签页的 timer 降频，设备休眠期间更是完全不
  // 走，醒来后可能已经过期很久。visibilitychange 负责补上这一刀。
  if (typeof window !== 'undefined') {
    window.setInterval(enforceSession, SESSION_CHECK_INTERVAL_MS)
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') enforceSession()
    })
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    isAuthenticated,
    isAdmin,
    username,
    login,
    register,
    logout,
    checkSession,
    enforceSession,
    ensureValidSession,
  }
})
