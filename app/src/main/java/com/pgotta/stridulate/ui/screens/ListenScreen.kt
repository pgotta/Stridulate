package com.pgotta.stridulate.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.ui.Detection
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.components.RealSpectrogram
import com.pgotta.stridulate.ui.components.SpeciesThumbnail
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Ink
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim
import com.pgotta.stridulate.ui.theme.SpecBg

@Composable
fun ListenScreen(
    spectrogramColumns: List<FloatArray>,
    loudness: Float,
    detections: List<Detection>,
    elapsedSeconds: Double,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "s"
    )

    val secondsPerColumn = 4096.0 / 48_000.0
    val visibleSpan = (spectrogramColumns.size * secondsPerColumn).coerceAtLeast(0.1)
    val visibleStart = (elapsedSeconds - visibleSpan).coerceAtLeast(0.0)
    val markerFractions = detections.flatMap { it.occurrences }
        .filter { it.endSeconds >= visibleStart }
        .map { ((it.endSeconds - visibleStart) / visibleSpan).toFloat().coerceIn(0f, 1f) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow(
            title = "Listening",
            sub = "ROLLING ID · ACCEPTED CALLS ONLY",
            onBack = onCancel,
            status = "recording",
            statusOn = true
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).scale(pulseScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Danger, Color(0xFF7A221A)))),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Listening for night callers", fontFamily = Fraunces, fontSize = 19.sp, color = Parch)
                Text(
                    "FIRST RESULT AFTER ~5 SEC · ${elapsedSeconds.toInt()} SEC",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.5.sp,
                    color = Mute,
                    letterSpacing = 0.7.sp
                )
            }
        }

        Spacer(Modifier.height(11.dp))
        Box(
            Modifier.fillMaxWidth().height(205.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SpecBg)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(14.dp))
        ) {
            RealSpectrogram(
                columns = spectrogramColumns,
                modifier = Modifier.fillMaxSize(),
                markerFractions = markerFractions
            )
            Text(
                "AMBER MARKERS = ACCEPTED CALL WINDOWS",
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC08100E))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                fontFamily = JetBrainsMono,
                fontSize = 8.sp,
                color = Amber
            )
        }

        Spacer(Modifier.height(9.dp))
        Text(
            "Level ${(loudness * 100).toInt().coerceIn(0, 100)}% · rejected or disabled-tier guesses are not shown or logged.",
            fontFamily = JetBrainsMono,
            fontSize = 10.5.sp,
            color = Mute,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))

        if (detections.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(Panel).border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No accepted insect calls yet.\nLow-confidence output stays hidden.",
                    fontFamily = Inter,
                    fontSize = 13.sp,
                    color = ParchDim,
                    lineHeight = 19.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detections, key = { it.species.id }) { detection ->
                    LiveDetectionRow(detection)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "Stop & save to Log",
            onStop,
            Modifier.fillMaxWidth(),
            container = Danger,
            content = Color.White
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun LiveDetectionRow(detection: Detection) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(70.dp, 52.dp).clip(RoundedCornerShape(8.dp)).background(Ink)) {
            SpeciesThumbnail(detection.species, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(detection.species.common, fontFamily = Fraunces, fontSize = 15.sp, color = Parch)
            Text(
                detection.species.latin,
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                color = Mute
            )
            Text(
                "${detection.occurrences.size} accepted call${if (detection.occurrences.size == 1) "" else "s"}",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = ParchDim
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${detection.confidencePct}%", fontFamily = JetBrainsMono, fontSize = 16.sp, color = Biolume)
            Text("peak ${detection.peakConfidencePct}%", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute)
        }
    }
}
