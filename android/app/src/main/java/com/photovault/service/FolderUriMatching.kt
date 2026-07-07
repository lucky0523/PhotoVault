package com.photovault.service

import java.net.URLDecoder

/**
 * 归一化用于比较的备份文件夹 key。
 *
 * SAF tree uri（如 `content://.../tree/primary%3ADCIM%2FCamera`）在数据库里以
 * 原始的 percent 编码形式存储，但经过导航层的 URL 编解码（Compose Navigation
 * 会对 path 参数自动解码一次，NavGraph 又显式解码一次）后，会以已解码形式
 * （`content://.../tree/primary:DCIM/Camera`）传到重新备份流程。
 *
 * 逐字符匹配会因此失败，导致 folder 找不到、聚合计数不更新、存储策略回退默认。
 * 对任意形式再做一次幂等的 URLDecode 归一，使两种形式产生相同 key，从而按内容
 * 匹配文件夹。纯函数，便于测试。
 */
fun canonicalFolderKey(folderUri: String): String =
    try {
        URLDecoder.decode(folderUri, "UTF-8")
    } catch (e: Exception) {
        folderUri
    }
