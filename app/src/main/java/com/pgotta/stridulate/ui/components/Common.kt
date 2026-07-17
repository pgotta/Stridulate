package com.pgotta.stridulate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Ink
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Amber) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = JetBrainsMono,
        fontSize = 11.sp,
        letterSpacing = 2.2.sp
    )
}

@Composable
fun Chip(text: String, accent: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.015f))
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (accent) Biolume else ParchDim,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Amber,
    content: Color = Ink
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    ) {
        Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Parch)
    ) {
        Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** The circular confidence ring from the match card. */
@Composable
fun ConfidenceRing(pct: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = 11.dp.toPx()
            val d = size.minDimension - strokePx
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(
                color = Color(0xFF20302C),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Biolume,
                startAngle = -90f,
                sweepAngle = 360f * pct / 100f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$pct",
                fontFamily = Fraunces,
                fontWeight = FontWeight.SemiBold,
                fontSize = 44.sp,
                color = Parch
            )
            Text(
                "% AUDIO",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = Mute,
                letterSpacing = 1.6.sp
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = Amber,
        fontFamily = JetBrainsMono,
        fontSize = 11.sp,
        letterSpacing = 2.sp
    )
}

fun confidenceWord(pct: Int): String = when {
    pct >= 80 -> "Confident"
    pct >= 55 -> "Probable"
    else -> "Uncertain"
}

fun confidenceColor(pct: Int): Color = when {
    pct >= 80 -> Biolume
    pct >= 55 -> Amber
    else -> Danger
}
