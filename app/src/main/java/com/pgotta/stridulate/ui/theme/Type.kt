package com.pgotta.stridulate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The app builds and runs with platform fonts out of the box (serif / sans /
 * monospace), which already give the right *structure*: a serif display, a sans
 * body, and a monospace data face.
 *
 * To match the web mockup exactly, drop these OFL-licensed TTFs into
 * res/font/ and switch the three families below to FontFamily(Font(R.font.…)):
 *   Fraunces  → serif display  (fraunces_regular/medium/semibold/black.ttf)
 *   Inter     → sans body      (inter_regular/medium/semibold.ttf)
 *   JetBrains Mono → data face (jetbrainsmono_regular/medium/bold.ttf)
 * See the Fonts section in the project README. Keeping platform fallbacks here
 * means a fresh clone compiles with zero missing-resource errors.
 */

val Fraunces: FontFamily = FontFamily.Serif
val Inter: FontFamily = FontFamily.SansSerif
val JetBrainsMono: FontFamily = FontFamily.Monospace

val StridulateTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp)
)
