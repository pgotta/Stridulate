package com.pgotta.stridulate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- Stridulate palette (ported from the web mockup) ---
val Ink        = Color(0xFF0B1113)
val Ink2       = Color(0xFF0F171A)
val Panel      = Color(0xFF131E20)
val Panel2     = Color(0xFF182629)
val Line       = Color(0xFF25373A)
val Amber       = Color(0xFFE8A13A)
val AmberSoft  = Color(0xFFF2C377)
val Biolume    = Color(0xFFB6E84B)
val Biolume2   = Color(0xFF66D59A)
val Parch      = Color(0xFFEDE7D7)   // warm white — used for headers (NOT yellow)
val ParchDim   = Color(0xFFA9B2A6)
val Mute       = Color(0xFF71827D)
val Danger     = Color(0xFFE86A5A)
val SpecBg     = Color(0xFF08100E)

private val StridulateColors = darkColorScheme(
    primary = Biolume,
    onPrimary = Color(0xFF0B1A0C),
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = Parch,
    surface = Panel,
    onSurface = Parch,
    surfaceVariant = Panel2,
    onSurfaceVariant = ParchDim,
    outline = Line,
    error = Danger
)

@Composable
fun StridulateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StridulateColors,
        typography = StridulateTypography,
        content = content
    )
}
