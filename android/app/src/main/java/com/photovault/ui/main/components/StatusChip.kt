package com.photovault.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 可复用的备份状态标签：一个彩色圆点加「标签 数量」文本，背景为对应颜色的低透明度填充。
 *
 * 供本地 Tab（LocalTab）与云端 Tab（CloudTab）共用，用于展示各类备份状态数量。
 *
 * @param label 状态标签文本，如「已备份」「未备份」「回收站」「已删除」
 * @param count 该状态对应的数量
 * @param color 该状态的主题色，用于圆点、文本与半透明背景
 * @param modifier 外部布局修饰符
 */
@Composable
fun StatusChip(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * 云端状态颜色约定。
 *
 * - [BackedUp] 已备份：绿色
 * - [Trashed] 回收站：橙色
 * - [Purged] 已删除：红色
 */
object CloudStatusColors {
    val BackedUp = Color(0xFF34C759) // 绿色
    val Trashed = Color(0xFFFF9500)  // 橙色
    val Purged = Color(0xFFFF3B30)   // 红色
}
