package com.pgotta.stridulate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.pgotta.stridulate.ui.theme.SpecBg
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Bioluminescent heatmap ramp: dark → teal → biolume → amber. Ported from web. */
fun heat(t: Float): Color {
    val stops = arrayOf(
        floatArrayOf(8f, 14f, 12f),
        floatArrayOf(20f, 60f, 52f),
        floatArrayOf(102f, 213f, 154f),
        floatArrayOf(182f, 232f, 75f),
        floatArrayOf(240f, 220f, 120f)
    )
    val tc = t.coerceIn(0f, 1f)
    val f = tc * (stops.size - 1)
    val i = f.toInt()
    val k = f - i
    val a = stops[i]; val b = stops[minOf(i + 1, stops.size - 1)]
    return Color(
        (a[0] + (b[0] - a[0]) * k) / 255f,
        (a[1] + (b[1] - a[1]) * k) / 255f,
        (a[2] + (b[2] - a[2]) * k) / 255f
    )
}

/**
 * Procedurally-drawn, species-accurate spectrogram illustration for the field
 * guide, list rows, and result cards. Direct port of the web mockup's draw():
 *   cricket → narrow band with regular pulse train
 *   cicada  → broadband dense buzz that swells
 *   katydid → raspy repeated bursts
 */
@Composable
fun ProceduralSpectrogram(kind: String, modifier: Modifier = Modifier, seed: Int = 7) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        drawRect(SpecBg, size = Size(w, h))
        val cols = (w / 6f).toInt().coerceAtLeast(30)
        val rows = (h / 6f).toInt().coerceAtLeast(16)
        val cw = w / cols; val ch = h / rows
        val rnd = Random(seed)
        for (x in 0 until cols) {
            for (y in 0 until rows) {
                val fy = y.toFloat() / rows
                var v: Float
                when (kind) {
                    "cricket" -> {
                        val band = exp(-((fy - 0.5f) / 0.09f).pow(2))
                        val pulse = (sin(x.toFloat() / cols * Math.PI.toFloat() * 2 * 22) + 1) / 2
                        v = band * (0.35f + 0.65f * pulse.pow(3)) + rnd.nextFloat() * 0.05f
                    }
                    "cicada" -> {
                        val band = exp(-((fy - 0.42f) / 0.32f).pow(2))
                        val swell = 0.55f + 0.45f * sin(x.toFloat() / cols * Math.PI.toFloat() * 1.3f)
                        v = band * swell * (0.6f + 0.4f * rnd.nextFloat())
                    }
                    else -> { // katydid
                        val band = exp(-((fy - 0.4f) / 0.22f).pow(2))
                        val burst = if ((x * 7) % maxOf(3, cols / 5) < 3) 1f else 0.12f
                        v = band * burst * (0.5f + 0.5f * rnd.nextFloat())
                    }
                }
                if (v < 0.06f) continue
                drawRect(
                    color = heat(v).copy(alpha = (v * 1.15f).coerceIn(0f, 1f)),
                    topLeft = Offset(x * cw, y * ch),
                    size = Size(cw + 0.6f, ch + 0.6f)
                )
            }
        }
    }
}

/**
 * Renders a REAL captured/decoded spectrogram (list of columns, each a
 * bottom→top magnitude array). Used for imported-clip and live results.
 */
@Composable
fun RealSpectrogram(
    columns: List<FloatArray>,
    modifier: Modifier = Modifier,
    markerFractions: List<Float> = emptyList(),
    activeMarkerFraction: Float? = null
) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        drawRect(SpecBg, size = Size(w, h))
        if (columns.isEmpty()) return@Canvas
        val cw = w / columns.size
        val rows = columns[0].size
        val rowH = h / rows
        columns.forEachIndexed { c, col ->
            for (r in 0 until rows) {
                val v = col[r]
                if (v < 0.07f) continue
                drawRect(
                    color = heat(v).copy(alpha = (v * 1.2f).coerceIn(0f, 1f)),
                    topLeft = Offset(c * cw, r * rowH),
                    size = Size(cw + 0.6f, rowH + 0.6f)
                )
            }
        }
        markerFractions.forEach { fraction ->
            val x = fraction.coerceIn(0f, 1f) * w
            drawLine(
                color = Color(0x99F0B44D),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 2.0f,
                cap = StrokeCap.Round
            )
        }
        activeMarkerFraction?.let { fraction ->
            val x = fraction.coerceIn(0f, 1f) * w
            drawLine(
                color = Color(0xFFFFB13B),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 4.5f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = Color(0xFFFFB13B),
                radius = 5.5f,
                center = Offset(x, 9f)
            )
        }
    }
}
