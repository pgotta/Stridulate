package com.pgotta.stridulate.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.components.RealSpectrogram
import com.pgotta.stridulate.ui.theme.*

@Composable
fun ListenScreen(
    spectrogramColumns: List<FloatArray>,
    loudness: Float,
    liveCandidate: Candidate?,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "s"
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow(title = "Listening", sub = "MIC · TAP STOP TO ID", onBack = onCancel,
            status = "recording", statusOn = true)

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).scale(pulseScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Danger, Color(0xFF7A221A)))),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(15.dp).clip(CircleShape).background(Color.White)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Listening for night callers", fontFamily = Fraunces, fontSize = 20.sp, color = Parch)
                Text("MIC · 48 kHz · GAIN AUTO", fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = Mute, letterSpacing = 1.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().height(300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SpecBg)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(14.dp))
        ) {
            RealSpectrogram(spectrogramColumns, Modifier.fillMaxSize())
            liveCandidate?.let { cand ->
                val pct = (cand.confidence * 100).toInt()
                Column(
                    Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xCC0B110F))
                        .border(BorderStroke(1.dp, Line), RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp)
                        .widthIn(min = 160.dp)
                ) {
                    Text("MOST LIKELY", fontFamily = JetBrainsMono, fontSize = 9.sp, color = ParchDim)
                    cand.species?.let { species -> Text(species.common, fontFamily = Fraunces, fontSize = 15.sp, color = Parch) }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                        color = Biolume, trackColor = Color(0xFF20302C)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("$pct%", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Biolume)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Live level ${(loudness * 100).toInt().coerceIn(0, 100)}% · get within a few metres of the caller and hold steady for at least 6 seconds of clear song.",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = Mute, lineHeight = 18.sp
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Stop & identify", onStop, Modifier.fillMaxWidth(),
            container = Danger, content = Color.White)
        Spacer(Modifier.height(16.dp))
    }
}
