/**
 * 结束会话这一个动作的唯一实现。
 *
 * 三个地方需要它：http 拦截器（刷新令牌也失效了）、会话巡检（挂着不动的标签页
 * 到期）、用户主动退出。它们必须做同样的两件事 —— 清凭证、回登录页 —— 而且顺
 * 序不能颠倒：先清空存储（这会通知 store 同步 ref），`isAuthenticated` 才会变
 * 成 false，导航守卫才不会把跳向登录页的导航当成"已登录用户误入登录页"而弹回
 * 主页。
 */

import router from '@/router'
import { clearCredentials } from '@/utils/authTokens'

/**
 * 清空凭证并回到登录页。
 *
 * @param options.redirect 是否把当前地址记为登录后的回跳目标，默认记。
 */
export function endSession(options: { redirect?: boolean } = {}): void {
  const { redirect = true } = options

  clearCredentials()

  const current = router.currentRoute.value
  if (current.name === 'Login') return

  const query =
    redirect && current.fullPath && current.fullPath !== '/'
      ? { redirect: current.fullPath }
      : undefined

  router.push({ name: 'Login', query })
}
