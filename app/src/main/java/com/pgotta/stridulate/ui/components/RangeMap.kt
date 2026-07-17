package com.pgotta.stridulate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.R
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesPhoto
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Ink
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel2
import com.pgotta.stridulate.ui.theme.ParchDim
import kotlin.math.min

/**
 * Data-backed occurrence map.
 *
 * The previous implementation tinted the entire country for every species and
 * called it a range map. This version plots a bounded sample of research-grade
 * iNaturalist records on the lower-48 outline. The dots are explicitly labeled
 * as observation records rather than a complete biological range boundary.
 */
@Composable
fun RangeMap(sp: Species, modifier: Modifier = Modifier) {
    val map = ImageVector.vectorResource(R.drawable.us_map)
    val uriHandler = LocalUriHandler.current
    var sample by remember(sp.id) { mutableStateOf<SpeciesPhoto.ObservationSample?>(null) }
    var loading by remember(sp.id) { mutableStateOf(true) }
    var failed by remember(sp.id) { mutableStateOf(false) }

    LaunchedEffect(sp.id) {
        loading = true
        failed = false
        sample = SpeciesPhoto.observationsFor(sp.latin)
        failed = sample == null
        loading = false
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(178.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Panel2),
        contentAlignment = Alignment.Center
    ) {
        val mapLayer = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 13.dp)

        Image(
            imageVector = map,
            contentDescription = "Documented U.S. observations for ${sp.common}",
            modifier = mapLayer,
            contentScale = ContentScale.Fit
        )

        val points = sample?.points.orEmpty()
        if (points.isNotEmpty()) {
            Canvas(mapLayer) {
                // The bundled vector uses a 1000 x 620 equirectangular lower-48
                // viewport spanning approximately 125W..66W and 24N..50N.
                val scale = min(size.width / MAP_WIDTH, size.height / MAP_HEIGHT)
                val drawnWidth = MAP_WIDTH * scale
                val drawnHeight = MAP_HEIGHT * scale
                val left = (size.width - drawnWidth) / 2f
                val top = (size.height - drawnHeight) / 2f
                val radius = 2.7.dp.toPx()

                for (point in points) {
                    val nx = ((point.longitude - MIN_LON) / (MAX_LON - MIN_LON))
                        .toFloat().coerceIn(0f, 1f)
                    val ny = ((MAX_LAT - point.latitude) / (MAX_LAT - MIN_LAT))
                        .toFloat().coerceIn(0f, 1f)
                    val center = Offset(
                        x = left + nx * drawnWidth,
                        y = top + ny * drawnHeight
                    )
                    drawCircle(
                        color = Ink.copy(alpha = 0.75f),
                        radius = radius + 1.2.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Biolume.copy(alpha = 0.82f),
                        radius = radius,
                        center = center
                    )
                }
            }
        }

        when {
            loading -> CircularProgressIndicator(
                color = Biolume,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center)
            )
            failed || sample?.points.isNullOrEmpty() -> Text(
                text = "Observation map unavailable\nConnect to the internet and reopen this species.",
                color = Mute,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Ink.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        sample?.takeIf { it.points.isNotEmpty() }?.let { data ->
            val countText = if (data.totalResults > data.points.size) {
                "${data.points.size} plotted · ${data.totalResults} records"
            } else {
                "${data.points.size} plotted records"
            }
            Text(
                text = "iNATURALIST · RESEARCH GRADE · $countText",
                color = ParchDim,
                fontFamily = JetBrainsMono,
                fontSize = 8.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp)
                    .background(Ink.copy(alpha = 0.88f), RoundedCornerShape(5.dp))
                    .clickable(enabled = data.sourceUrl != null) {
                        data.sourceUrl?.let(uriHandler::openUri)
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

private const val MAP_WIDTH = 1000f
private const val MAP_HEIGHT = 620f
private const val MIN_LON = -125.0
private const val MAX_LON = -66.0
private const val MIN_LAT = 24.0
private const val MAX_LAT = 50.0
