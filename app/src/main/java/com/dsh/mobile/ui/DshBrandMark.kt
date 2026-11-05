package com.dsh.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R

/** Use the same mascot as the launcher and boot screen, without new artwork. */
@Composable
fun DshBrandMark(size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp))
        .background(com.dsh.mobile.ui.theme.LocalDshAccents.current.accent.copy(alpha = .10f))) {
        // The adaptive launcher asset includes transparent safe-zone padding.
        Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = "鲸鱼娘",
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f })
    }
}
