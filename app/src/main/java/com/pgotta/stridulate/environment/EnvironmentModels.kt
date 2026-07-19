package com.pgotta.stridulate.environment

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** How the user supplied observation location. */
enum class ContextMode { OFF, DEVICE, MANUAL }

enum class ContextStatus { DISABLED, REFRESHING, READY, STALE, ERROR }

enum class ContextRegion(val displayName: String) {
    NORTHEAST("Northeast U.S."),
    SOUTHEAST("Southeast U.S."),
    MIDWEST("Midwest U.S."),
    GREAT_PLAINS("Great Plains"),
    SOUTH_CENTRAL("South-central U.S."),
    MOUNTAIN_WEST("Mountain West"),
    PACIFIC("Pacific/West Coast"),
    UNKNOWN("Region unavailable");

    val profileTags: Set<String>
        get() = when (this) {
            NORTHEAST -> setOf("EAST", "NORTH", "NORTHEAST")
            SOUTHEAST -> setOf("EAST", "SOUTH", "SOUTHEAST")
            MIDWEST -> setOf("EAST", "CENTRAL", "NORTH", "MIDWEST")
            GREAT_PLAINS -> setOf("CENTRAL", "GREAT_PLAINS", "NORTH")
            SOUTH_CENTRAL -> setOf("CENTRAL", "SOUTH", "SOUTH_CENTRAL")
            MOUNTAIN_WEST -> setOf("WEST", "MOUNTAIN_WEST", "NORTH")
            PACIFIC -> setOf("WEST", "PACIFIC")
            UNKNOWN -> emptySet()
        }

    companion object {
        /** Deliberately broad U.S. regions: context support, not a range boundary. */
        fun fromCoordinates(latitude: Double, longitude: Double): ContextRegion = when {
            longitude <= -115.0 -> PACIFIC
            longitude <= -104.0 -> MOUNTAIN_WEST
            longitude <= -96.0 && latitude < 36.5 -> SOUTH_CENTRAL
            longitude <= -94.0 -> GREAT_PLAINS
            longitude <= -82.0 && latitude >= 37.0 -> MIDWEST
            latitude < 36.5 -> SOUTHEAST
            else -> NORTHEAST
        }
    }
}

/**
 * A snapshot of optional environmental context captured for an observation.
 *
 * The audio model remains primary. Weather is considered current for automatic use for ten
 * minutes, is allowed as a cautious scoring input for at most thirty minutes, and is retained
 * for up to two hours only as an offline display fallback.
 */
data class ObservationContext(
    val enabled: Boolean = false,
    val mode: ContextMode = ContextMode.OFF,
    val status: ContextStatus = ContextStatus.DISABLED,
    val locationLabel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val region: ContextRegion = ContextRegion.UNKNOWN,
    val temperatureF: Double? = null,
    val humidityPercent: Int? = null,
    val isDaylight: Boolean? = null,
    val temperatureObservedAtMillis: Long? = null,
    val timezoneId: String? = null,
    /** Time at which Stridulate successfully fetched this weather snapshot. */
    val refreshedAtMillis: Long? = null,
    val manualQuery: String? = null,
    val message: String = "Location context is off. Audio identification still works normally."
) {
    private fun localDateTime(nowMillis: Long = System.currentTimeMillis()): ZonedDateTime {
        val zone = runCatching { ZoneId.of(timezoneId ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
    }

    val monthIndex: Int get() = localDateTime().monthValue - 1
    val localHour: Int get() = localDateTime().hour
    val seasonLabel: String
        get() = when (localDateTime().monthValue) {
            12, 1, 2 -> "Winter"
            3, 4, 5 -> "Spring"
            6, 7, 8 -> "Summer"
            else -> "Fall"
        }

    val dayPeriodLabel: String
        get() = when (isDaylight) {
            true -> "Day"
            false -> "Night"
            null -> if (localHour >= 18 || localHour < 6) "Night" else "Day"
        }

    val temperatureLabel: String
        get() = temperatureF?.let { String.format(Locale.US, "%.0f°F", it) }
            ?: "Temperature unavailable"

    val humidityLabel: String?
        get() = humidityPercent?.let { "$it% humidity" }

    /** Age of the successful network fetch performed by Stridulate. */
    val weatherAgeMillis: Long?
        get() = refreshedAtMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }

    /** Age of the timestamp returned by the weather provider, when available. */
    val weatherSourceAgeMillis: Long?
        get() = temperatureObservedAtMillis?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        }

    /** Conservative temperature age: whichever is older, fetch time or provider timestamp. */
    val effectiveTemperatureAgeMillis: Long?
        get() {
            val ages = listOfNotNull(weatherAgeMillis, weatherSourceAgeMillis)
            return ages.maxOrNull()
        }

    /** Fresh enough that the background scheduler does not need a network refresh. */
    val isFresh: Boolean
        get() = weatherAgeMillis?.let { it < AUTO_REFRESH_MILLIS } == true

    /** Temperature may affect a sourced species profile only while this is true. */
    val isTemperatureCurrentForScoring: Boolean
        get() = temperatureF != null &&
            effectiveTemperatureAgeMillis?.let { it <= MAX_SCORING_AGE_MILLIS } == true

    /** A failed refresh may display the previous temperature only while this is true. */
    val hasUsableTemperatureFallback: Boolean
        get() = temperatureF != null &&
            weatherAgeMillis?.let { it <= MAX_FALLBACK_AGE_MILLIS } == true

    val weatherAgeLabel: String
        get() = ageLabel(weatherAgeMillis, "Updated") ?: "Not updated"

    val weatherSourceAgeLabel: String?
        get() = ageLabel(weatherSourceAgeMillis, "Weather observation")

    private fun ageLabel(ageMillis: Long?, prefix: String): String? {
        val age = ageMillis ?: return null
        val minutes = age / 60_000L
        return when {
            minutes < 1L -> "$prefix just now"
            minutes == 1L -> "$prefix 1 minute ago"
            minutes < 60L -> "$prefix $minutes minutes ago"
            else -> {
                val hours = minutes / 60L
                if (hours == 1L) "$prefix 1 hour ago" else "$prefix $hours hours ago"
            }
        }
    }

    companion object {
        /** Request fresh weather automatically in the background after this age. */
        const val AUTO_REFRESH_MILLIS: Long = 10L * 60L * 1000L

        /** Do not use an older temperature in species-specific scoring. */
        const val MAX_SCORING_AGE_MILLIS: Long = 30L * 60L * 1000L

        /** Keep an older value only as an offline display fallback. */
        const val MAX_FALLBACK_AGE_MILLIS: Long = 2L * 60L * 60L * 1000L
    }
}
