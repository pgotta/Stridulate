package com.pgotta.stridulate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesPhoto

/** Cached real insect photo with the existing waveform artwork as a safe fallback. */
@Composable
fun SpeciesThumbnail(species: Species, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var photo by remember(species.id) { mutableStateOf<SpeciesPhoto.PhotoInfo?>(null) }
    var failed by remember(species.id) { mutableStateOf(false) }

    LaunchedEffect(species.id) {
        failed = false
        photo = SpeciesPhoto.photoFor(context, species)
    }

    Box(modifier) {
        ProceduralSpectrogram(species.group, Modifier.fillMaxSize())
        photo?.let { info ->
            AsyncImage(
                model = info.imageUrl,
                contentDescription = species.common,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                onSuccess = { failed = false }
            )
        }
        if (failed) ProceduralSpectrogram(species.group, Modifier.fillMaxSize())
    }
}
