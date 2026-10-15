package com.huoyi.photovault.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.semanticColorSet
import com.huoyi.photovault.ui.theme.statusSoftFill

@Composable
fun StatusChip(
    label: String,
    count: Int,
    tone: SemanticTone,
    modifier: Modifier = Modifier
) {
    // Same soft look as the folder-detail filter pills: faint translucent tint of
    // the status accent as background, vivid accent for dot, label and count.
    val colors = semanticColorSet(tone)
    val textColor = colors.accent
    Column(
        modifier = modifier
            .testTag("status_chip_${label}_container")
            .clip(RoundedCornerShape(8.dp))
            .background(statusSoftFill(colors.accent))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .testTag("status_chip_${label}_dot")
                    .clip(CircleShape)
                    .background(colors.accent)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                modifier = Modifier.testTag("status_chip_${label}_label"),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            count.toString(),
            modifier = Modifier.testTag("status_chip_${label}_count"),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
