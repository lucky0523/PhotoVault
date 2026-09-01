<!--
  错投的飞牛授权路由。

  /app-auth/* 是飞牛系统 UI 的路由，不属于本应用。但只要有人（或 SDK）把它拼到了
  本应用的 origin 上，请求就会被 FastAPI 的 SPA catch-all 兜成 index.html，而
  Vue Router 没有匹配路由 —— 结果是**一个纯白页面，控制台也没有任何报错**，完全
  看不出发生了什么。

  这个页面就是把那个哑失败变成能自我解释的失败：把收到的路由和参数原样列出来，
  并指明真正该去的地址。它本身不做任何授权动作。

  典型触发原因：SDK 的 getAppAuthBaseUrl() 在独立页面下返回 window.location.origin，
  而本应用挂在自己的端口上（不是统一网关的 /app/<appName>/），于是基址取成了本应用。
  详见 utils/fnos.ts 里「飞牛系统地址」一节。

  本文件只在飞牛构建（__FNOS_BUILD__）里被打包。
-->
<template>
  <div class="misdirected">
    <el-result icon="warning" title="这是飞牛系统的授权路由，不是 PhotoVault 的页面">
      <template #sub-title>
        <span>
          它被拼到了本应用的地址上，所以只能得到一个空白页。授权页需要打开在飞牛的地址上。
        </span>
      </template>

      <template #extra>
        <div class="misdirected-body">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="收到的路径">
              <span class="misdirected-mono">{{ route.path }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="本应用 origin">
              <span class="misdirected-mono">{{ currentOrigin }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="appName" label="appName">
              <span class="misdirected-mono">{{ appName }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="suggestedUrl" label="应该打开">
              <span class="misdirected-mono">{{ suggestedUrl }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <p class="misdirected-tip">
            回到「设置 → 飞牛目录授权」，在「飞牛系统地址」里填写飞牛 Web 管理界面的地址
            （默认端口 5666）后重试；或者通过飞牛桌面打开 PhotoVault，那条路径不需要拼地址。
          </p>

          <div class="misdirected-actions">
            <el-button v-if="suggestedUrl" type="primary" @click="openSuggested">
              用推测的飞牛地址打开
            </el-button>
            <el-button @click="goBackToTestPage">回到目录授权页</el-button>
          </div>
        </div>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { guessHostOrigin } from '@/utils/fnos'

const route = useRoute()
const router = useRouter()

const currentOrigin = window.location.origin

const appName = computed(() => String(route.query.appName ?? ''))

/**
 * 把当前这条错投的 URL 原样搬到推测出的飞牛地址上。
 *
 * 只换 origin，路径和查询参数全部保留 —— 里面的 redirectUri / state / sidebarGroup
 * 都是上一步生成好的，重新构造反而容易出错。
 */
const suggestedUrl = computed(() => {
  const host = guessHostOrigin()
  if (!host || host === currentOrigin) return ''
  try {
    const target = new URL(host)
    target.pathname = route.path
    target.search = window.location.search
    return target.toString()
  } catch {
    return ''
  }
})

function openSuggested(): void {
  if (suggestedUrl.value) window.location.assign(suggestedUrl.value)
}

function goBackToTestPage(): void {
  void router.push({ name: 'FnosSharedAccess' })
}
</script>

<style scoped>
.misdirected {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  box-sizing: border-box;
  background: var(--el-bg-color-page, #f5f7fa);
}

.misdirected-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 620px;
  text-align: left;
}

.misdirected-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.misdirected-tip {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.misdirected-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
