package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.ui.theme.*

/** Reusable top bar with an optional back button and a status dot. */
@Composable
fun AppBarRow(
    title: String,
    sub: String,
    onBack: (() -> Unit)? = null,
    status: String? = null,
    statusOn: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(BorderStroke(1.dp, Line), RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Text("‹", color = Parch, fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(title, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp, color = Parch)
            Text(sub, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Mute, letterSpacing = 1.sp)
        }
        Spacer(Modifier.weight(1f))
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape)
                    .background(if (statusOn) Biolume else Mute))
                Spacer(Modifier.width(6.dp))
                Text(status, fontFamily = JetBrainsMono, fontSize = 10.5.sp,
                    color = if (statusOn) Biolume else Mute)
            }
        }
        trailing?.invoke()
    }
}
