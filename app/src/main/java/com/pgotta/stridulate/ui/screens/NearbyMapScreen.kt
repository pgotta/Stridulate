package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.R
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.environment.ContextRegion
import com.pgotta.stridulate.environment.ObservationContext
import com.pgotta.stridulate.environment.SpeciesContextProfile
import com.pgotta.stridulate.ui.components.SpeciesThumbnail
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Panel2
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim
import kotlin.math.min

/** Broad, conservative regional shortlist. It is not a biological range boundary or live sightings map. */
@Composable
fun NearbyMapScreen(
    species: List<Species>,
    reliabilityFor: (Species) -> ReliabilityInfo,
    profileFor: (Species) -> SpeciesContextProfile?,
    observationContext: ObservationContext,
    onBack: () -> Unit,
    onOpenGuide: (String) -> Unit
) {
    val initialRegion = observationContext.region.takeUnless { it == ContextRegion.UNKNOWN }
    var selectedRegion by remember(initialRegion) { mutableStateOf(initialRegion) }
    var includeExperimental by remember { mutableStateOf(false) }
    var activeOnly by remember { mutableStateOf(true) }
    val month = observationContext.monthIndex

    val matches = remember(species, selectedRegion, includeExperimental, activeOnly, month) {
        val region = selectedRegion
        if (region == null) emptyList() else species.filter { item ->
            val reliability = reliabilityFor(item)
            val profile = profileFor(item) ?: return@filter false
            val tierAllowed = reliability.tier in setOf(ReliabilityTier.VERIFIED, ReliabilityTier.GOOD) ||
                (includeExperimental && reliability.tier == ReliabilityTier.EXPERIMENTAL)
            val regionAllowed = "NATIONWIDE" in profile.regions || profile.regions.any(region.profileTags::contains)
            val active = item.months.getOrElse(month) { 0 } == 1
            tierAllowed && regionAllowed && (!activeOnly || active)
        }.sortedWith(
            compareBy<Species> {
                when (reliabilityFor(it).tier) {
                    ReliabilityTier.VERIFIED -> 0
                    ReliabilityTier.GOOD -> 1
                    ReliabilityTier.EXPERIMENTAL -> 2
                    else -> 3
                }
            }.thenBy { it.common }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow(
            title = "Range map",
            sub = selectedRegion?.displayName?.uppercase() ?: "CHOOSE A U.S. REGION",
            onBack = onBack
        )
        Text(
            "This shows species with vetted broad-region profiles in the current model. It is a conservative shortlist—not an exact range boundary and not proof that a species is present nearby.",
            fontFamily = Inter,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = ParchDim
        )
        Spacer(Modifier.height(10.dp))
        RegionOverviewMap(selectedRegion, observationContext)
        Spacer(Modifier.height(9.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(ContextRegion.entries.filterNot { it == ContextRegion.UNKNOWN }) { region ->
                MapPill(region.shortName(), selectedRegion == region) { selectedRegion = region }
            }
        }
        Spacer(Modifier.height(7.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                MapPill(
                    label = if (activeOnly) "Active now" else "All seasons",
                    selected = activeOnly,
                    onClick = { activeOnly = !activeOnly }
                )
            }
            item {
                MapPill(
                    label = if (includeExperimental) "Experimental on" else "Verified + Good",
                    selected = includeExperimental,
                    onClick = { includeExperimental = !includeExperimental }
                )
            }
        }
        Spacer(Modifier.height(11.dp))

        when {
            selectedRegion == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Choose a broad region above.\nEnable Observation context on Home to select it automatically.",
                        fontFamily = Inter,
                        color = Mute,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
            matches.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (activeOnly) {
                            "No enabled model species have both a vetted ${selectedRegion!!.displayName} profile and an active-month flag right now. Try All seasons."
                        } else {
                            "No enabled model species have a vetted profile for this region."
                        },
                        fontFamily = Inter,
                        color = Mute,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Species to compare", fontFamily = Fraunces, fontSize = 18.sp, color = Parch)
                    Text("${matches.size} MATCHES", fontFamily = JetBrainsMono, fontSize = 9.5.sp, color = Biolume)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(matches, key = { it.id }) { item ->
                        NearbySpeciesRow(
                            species = item,
                            reliability = reliabilityFor(item),
                            profile = profileFor(item)!!,
                            activeNow = item.months.getOrElse(month) { 0 } == 1,
                            onClick = { onOpenGuide(item.id) }
                        )
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RegionOverviewMap(region: ContextRegion?, context: ObservationContext) {
    val map = ImageVector.vectorResource(R.drawable.us_map)
    val layer = Modifier.fillMaxWidth().height(155.dp).padding(horizontal = 12.dp, vertical = 10.dp)
    Box(
        Modifier.fillMaxWidth().height(155.dp).background(Panel2, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            imageVector = map,
            contentDescription = "Broad U.S. region map",
            modifier = layer,
            contentScale = ContentScale.Fit
        )
        Canvas(layer) {
            val scale = min(size.width / MAP_WIDTH, size.height / MAP_HEIGHT)
            val drawnWidth = MAP_WIDTH * scale
            val drawnHeight = MAP_HEIGHT * scale
            val left = (size.width - drawnWidth) / 2f
            val top = (size.height - drawnHeight) / 2f
            region?.highlight()?.let { highlight ->
                drawOval(
                    color = Biolume.copy(alpha = 0.22f),
                    topLeft = Offset(left + highlight.x * drawnWidth, top + highlight.y * drawnHeight),
                    size = Size(highlight.width * drawnWidth, highlight.height * drawnHeight)
                )
            }
            val lat = context.latitude
            val lon = context.longitude
            if (lat != null && lon != null && region == context.region) {
                val x = ((lon - MIN_LON) / (MAX_LON - MIN_LON)).toFloat().coerceIn(0f, 1f)
                val y = ((MAX_LAT - lat) / (MAX_LAT - MIN_LAT)).toFloat().coerceIn(0f, 1f)
                val center = Offset(left + x * drawnWidth, top + y * drawnHeight)
                drawCircle(Color.Black.copy(alpha = 0.65f), radius = 6.dp.toPx(), center = center)
                drawCircle(Amber, radius = 4.dp.toPx(), center = center)
            }
        }
        Text(
            text = region?.displayName ?: "Choose a region",
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(Color(0xDD08100E), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            fontFamily = JetBrainsMono,
            fontSize = 9.sp,
            color = if (region == null) Mute else Biolume
        )
    }
}

@Composable
private fun NearbySpeciesRow(
    species: Species,
    reliability: ReliabilityInfo,
    profile: SpeciesContextProfile,
    activeNow: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp))
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp, 54.dp).background(Panel2, RoundedCornerShape(8.dp))) {
            SpeciesThumbnail(species, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(species.common, fontFamily = Fraunces, fontSize = 15.5.sp, color = Parch)
            Text(
                species.latin,
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontSize = 11.5.sp,
                color = Mute
            )
            Text(profile.coverageNote, fontFamily = Inter, fontSize = 10.5.sp, color = ParchDim, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(7.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                reliability.tier.displayName.uppercase(),
                fontFamily = JetBrainsMono,
                fontSize = 8.5.sp,
                color = when (reliability.tier) {
                    ReliabilityTier.VERIFIED -> Biolume
                    ReliabilityTier.GOOD -> Amber
                    ReliabilityTier.EXPERIMENTAL -> Danger
                    else -> Mute
                }
            )
            Text(
                if (activeNow) "ACTIVE NOW" else "OFF SEASON",
                fontFamily = JetBrainsMono,
                fontSize = 8.sp,
                color = if (activeNow) Biolume else Mute
            )
        }
    }
}

@Composable
private fun MapPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Biolume else Panel, RoundedCornerShape(100.dp))
            .border(BorderStroke(1.dp, if (selected) Biolume else Line), RoundedCornerShape(100.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = if (selected) Color(0xFF0B1A0C) else ParchDim
        )
    }
}

private data class RegionHighlight(val x: Float, val y: Float, val width: Float, val height: Float)

private fun ContextRegion.highlight(): RegionHighlight = when (this) {
    ContextRegion.NORTHEAST -> RegionHighlight(0.75f, 0.15f, 0.22f, 0.30f)
    ContextRegion.SOUTHEAST -> RegionHighlight(0.63f, 0.50f, 0.28f, 0.34f)
    ContextRegion.MIDWEST -> RegionHighlight(0.51f, 0.25f, 0.26f, 0.34f)
    ContextRegion.GREAT_PLAINS -> RegionHighlight(0.38f, 0.28f, 0.22f, 0.40f)
    ContextRegion.SOUTH_CENTRAL -> RegionHighlight(0.40f, 0.56f, 0.28f, 0.32f)
    ContextRegion.MOUNTAIN_WEST -> RegionHighlight(0.17f, 0.22f, 0.26f, 0.48f)
    ContextRegion.PACIFIC -> RegionHighlight(0.02f, 0.16f, 0.20f, 0.58f)
    ContextRegion.UNKNOWN -> RegionHighlight(0f, 0f, 0f, 0f)
}

private fun ContextRegion.shortName(): String = when (this) {
    ContextRegion.NORTHEAST -> "Northeast"
    ContextRegion.SOUTHEAST -> "Southeast"
    ContextRegion.MIDWEST -> "Midwest"
    ContextRegion.GREAT_PLAINS -> "Plains"
    ContextRegion.SOUTH_CENTRAL -> "South-central"
    ContextRegion.MOUNTAIN_WEST -> "Mountain"
    ContextRegion.PACIFIC -> "Pacific"
    ContextRegion.UNKNOWN -> "Unknown"
}

private const val MAP_WIDTH = 1000f
private const val MAP_HEIGHT = 620f
private const val MIN_LON = -125.0
private const val MAX_LON = -66.0
private const val MIN_LAT = 24.0
private const val MAX_LAT = 50.0
