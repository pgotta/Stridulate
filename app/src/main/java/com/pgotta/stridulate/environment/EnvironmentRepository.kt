package com.pgotta.stridulate.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Optional privacy-controlled observation context.
 *
 * Audio inference remains fully on-device. When context is enabled, only rounded coordinates
 * or a manual city/ZIP are sent to Open-Meteo for current weather. A manual refresh always
 * bypasses the cache. Automatic refresh runs independently after ten minutes and never blocks
 * microphone recording.
 */
class EnvironmentRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ObservationContext> = _state

    fun disable() {
        val disabled = ObservationContext()
        _state.value = disabled
        prefs.edit().clear().apply()
    }

    fun markPermissionDenied() {
        val current = _state.value
        val next = current.copy(
            enabled = true,
            mode = ContextMode.DEVICE,
            status = ContextStatus.ERROR,
            message = "Approximate location permission was not granted. Use city/ZIP instead, or turn context off."
        )
        save(next)
    }

    /** Enable or refresh device context using a present-time location fix when possible. */
    suspend fun useDeviceLocation(forceFreshLocation: Boolean = true) {
        val refreshing = _state.value.copy(
            enabled = true,
            mode = ContextMode.DEVICE,
            status = ContextStatus.REFRESHING,
            message = "Getting current approximate location and weather…"
        )
        _state.value = refreshing

        if (!hasLocationPermission()) {
            markPermissionDenied()
            return
        }

        val location = currentOrRecentLocation(forceFreshLocation)
        if (location == null) {
            val fallback = refreshing.copy(
                status = ContextStatus.ERROR,
                message = "A current location fix was unavailable. Try again outdoors or enter a city/ZIP."
            )
            save(fallback)
            return
        }

        val latitude = roundCoordinate(location.latitude)
        val longitude = roundCoordinate(location.longitude)
        val region = ContextRegion.fromCoordinates(latitude, longitude)
        refreshCoordinate(
            latitude = latitude,
            longitude = longitude,
            mode = ContextMode.DEVICE,
            locationLabel = "Approximate location · ${region.displayName}",
            manualQuery = null
        )
    }

    suspend fun useManualLocation(query: String) {
        val cleaned = query.trim()
        if (cleaned.length < 2) {
            val error = _state.value.copy(
                enabled = true,
                mode = ContextMode.MANUAL,
                status = ContextStatus.ERROR,
                message = "Enter a U.S. city or ZIP code."
            )
            save(error)
            return
        }

        _state.value = _state.value.copy(
            enabled = true,
            mode = ContextMode.MANUAL,
            status = ContextStatus.REFRESHING,
            manualQuery = cleaned,
            message = "Looking up $cleaned and current weather…"
        )

        try {
            val location = geocode(cleaned)
                ?: throw IllegalStateException("No matching U.S. city or ZIP was found.")
            refreshCoordinate(
                latitude = location.latitude,
                longitude = location.longitude,
                mode = ContextMode.MANUAL,
                locationLabel = location.label,
                manualQuery = cleaned,
                knownTimezone = location.timezone
            )
        } catch (e: Exception) {
            val error = _state.value.copy(
                enabled = true,
                mode = ContextMode.MANUAL,
                status = ContextStatus.ERROR,
                message = e.message ?: "Location lookup failed. Check your connection and try again."
            )
            save(error)
        }
    }

    /** Automatic ten-minute refresh. It is safe to call often and never owns recording UI. */
    suspend fun refreshIfStale() {
        val current = _state.value
        if (!current.enabled || current.status == ContextStatus.REFRESHING || current.isFresh) return
        refreshCurrentContext(current, forceFreshDeviceLocation = false)
    }

    /** User-requested refresh always asks for fresh device context when available. */
    suspend fun refreshNow() {
        val current = _state.value
        if (!current.enabled || current.status == ContextStatus.REFRESHING) return
        refreshCurrentContext(current, forceFreshDeviceLocation = true)
    }

    private suspend fun refreshCurrentContext(
        current: ObservationContext,
        forceFreshDeviceLocation: Boolean
    ) {
        when (current.mode) {
            ContextMode.DEVICE -> {
                if (hasLocationPermission()) {
                    useDeviceLocation(forceFreshLocation = forceFreshDeviceLocation)
                } else {
                    markPermissionDenied()
                }
            }
            // The coordinates are already known. Refreshing weather should not geocode the same
            // city/ZIP every ten minutes.
            ContextMode.MANUAL -> refreshSavedCoordinates(current)
            ContextMode.OFF -> Unit
        }
    }

    private suspend fun refreshSavedCoordinates(current: ObservationContext) {
        val latitude = current.latitude ?: return
        val longitude = current.longitude ?: return
        _state.value = current.copy(
            status = ContextStatus.REFRESHING,
            message = "Refreshing current weather…"
        )
        refreshCoordinate(
            latitude = latitude,
            longitude = longitude,
            mode = current.mode,
            locationLabel = current.locationLabel ?: current.region.displayName,
            manualQuery = current.manualQuery,
            knownTimezone = current.timezoneId
        )
    }

    private suspend fun refreshCoordinate(
        latitude: Double,
        longitude: Double,
        mode: ContextMode,
        locationLabel: String,
        manualQuery: String?,
        knownTimezone: String? = null
    ) {
        val region = ContextRegion.fromCoordinates(latitude, longitude)
        try {
            val weather = fetchCurrentWeather(latitude, longitude)
            val ready = ObservationContext(
                enabled = true,
                mode = mode,
                status = ContextStatus.READY,
                locationLabel = locationLabel,
                latitude = latitude,
                longitude = longitude,
                region = region,
                temperatureF = weather.temperatureF,
                humidityPercent = weather.humidityPercent,
                isDaylight = weather.isDaylight,
                temperatureObservedAtMillis = weather.observedAtMillis,
                timezoneId = weather.timezone ?: knownTimezone,
                refreshedAtMillis = System.currentTimeMillis(),
                manualQuery = manualQuery,
                message = "Current weather updated just now. Background refresh runs after 10 minutes without delaying recording."
            )
            save(ready)
        } catch (e: Exception) {
            val prior = _state.value
            val sameCoordinates = prior.latitude == latitude && prior.longitude == longitude
            val canFallback = sameCoordinates && prior.hasUsableTemperatureFallback
            val fallback = ObservationContext(
                enabled = true,
                mode = mode,
                status = if (canFallback) ContextStatus.STALE else ContextStatus.READY,
                locationLabel = locationLabel,
                latitude = latitude,
                longitude = longitude,
                region = region,
                temperatureF = prior.temperatureF.takeIf { canFallback },
                humidityPercent = prior.humidityPercent.takeIf { canFallback },
                // Day/night falls back to the current local clock instead of stale API state.
                isDaylight = null,
                temperatureObservedAtMillis = prior.temperatureObservedAtMillis.takeIf { canFallback },
                timezoneId = knownTimezone ?: prior.timezoneId.takeIf { sameCoordinates },
                refreshedAtMillis = prior.refreshedAtMillis.takeIf { canFallback },
                manualQuery = manualQuery,
                message = if (canFallback) {
                    "Refresh failed. Showing ${prior.weatherAgeLabel.lowercase()} as an offline fallback; stale weather will not be used after 30 minutes."
                } else {
                    "Current weather is unavailable. Region, season and local-time context still work; audio identification is unchanged."
                }
            )
            save(fallback)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun currentOrRecentLocation(forceFresh: Boolean): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

        val recent = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (!forceFresh && recent != null &&
            System.currentTimeMillis() - recent.time <= RECENT_LOCATION_MAX_AGE_MILLIS
        ) return recent

        val provider = providers.firstOrNull() ?: return recent?.takeIf {
            System.currentTimeMillis() - it.time <= RECENT_LOCATION_MAX_AGE_MILLIS
        }
        val current = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }
        return current ?: recent?.takeIf {
            System.currentTimeMillis() - it.time <= RECENT_LOCATION_MAX_AGE_MILLIS
        }
    }

    private data class GeocodedLocation(
        val latitude: Double,
        val longitude: Double,
        val label: String,
        val timezone: String?
    )

    private suspend fun geocode(query: String): GeocodedLocation? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$encoded&count=1&language=en&format=json&countryCode=US"
        val root = readJson(url)
        val results = root.optJSONArray("results") ?: return@withContext null
        if (results.length() == 0) return@withContext null
        val first = results.getJSONObject(0)
        val name = first.getString("name")
        val admin1 = first.optString("admin1").takeIf { it.isNotBlank() }
        GeocodedLocation(
            latitude = roundCoordinate(first.getDouble("latitude")),
            longitude = roundCoordinate(first.getDouble("longitude")),
            label = listOfNotNull(name, admin1).distinct().joinToString(", "),
            timezone = first.optString("timezone").takeIf { it.isNotBlank() }
        )
    }

    private data class WeatherSnapshot(
        val temperatureF: Double,
        val humidityPercent: Int?,
        val isDaylight: Boolean?,
        val observedAtMillis: Long,
        val timezone: String?
    )

    private suspend fun fetchCurrentWeather(latitude: Double, longitude: Double): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,relative_humidity_2m,is_day" +
                "&temperature_unit=fahrenheit&timezone=auto"
            val root = readJson(url)
            val current = root.getJSONObject("current")
            val timezone = root.optString("timezone").takeIf { it.isNotBlank() }
            WeatherSnapshot(
                temperatureF = current.getDouble("temperature_2m"),
                humidityPercent = current.optInt("relative_humidity_2m", -1).takeIf { it in 0..100 },
                isDaylight = current.optInt("is_day", -1).takeIf { it == 0 || it == 1 }?.let { it == 1 },
                observedAtMillis = parseWeatherTime(current.optString("time"), timezone),
                timezone = timezone
            )
        }

    private fun parseWeatherTime(value: String, timezone: String?): Long {
        if (value.isBlank() || timezone.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            LocalDateTime.parse(value).atZone(ZoneId.of(timezone)).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }

    private fun readJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS.toInt()
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS.toInt()
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Stridulate/2.1 Android")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull()
                throw IllegalStateException(
                    reason?.takeIf { it.isNotBlank() } ?: "Context service returned HTTP $status."
                )
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun loadState(): ObservationContext {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return ObservationContext()
        val mode = runCatching { ContextMode.valueOf(prefs.getString(KEY_MODE, ContextMode.OFF.name)!!) }
            .getOrDefault(ContextMode.OFF)
        val savedStatus = runCatching {
            ContextStatus.valueOf(prefs.getString(KEY_STATUS, ContextStatus.STALE.name)!!)
        }.getOrDefault(ContextStatus.STALE)
        val refreshedAt = prefs.getNullableLong(KEY_REFRESHED_AT)
        val age = refreshedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        val fallbackExpired = age == null || age > ObservationContext.MAX_FALLBACK_AGE_MILLIS
        val status = when {
            savedStatus == ContextStatus.ERROR -> ContextStatus.ERROR
            age == null -> ContextStatus.STALE
            age >= ObservationContext.AUTO_REFRESH_MILLIS -> ContextStatus.STALE
            else -> savedStatus
        }
        val loaded = ObservationContext(
            enabled = true,
            mode = mode,
            status = status,
            locationLabel = prefs.getString(KEY_LOCATION_LABEL, null),
            latitude = prefs.getNullableDouble(KEY_LATITUDE),
            longitude = prefs.getNullableDouble(KEY_LONGITUDE),
            region = runCatching {
                ContextRegion.valueOf(prefs.getString(KEY_REGION, ContextRegion.UNKNOWN.name)!!)
            }.getOrDefault(ContextRegion.UNKNOWN),
            temperatureF = prefs.getNullableDouble(KEY_TEMPERATURE_F).takeUnless { fallbackExpired },
            humidityPercent = prefs.getNullableInt(KEY_HUMIDITY).takeUnless { fallbackExpired },
            isDaylight = prefs.getNullableBoolean(KEY_IS_DAYLIGHT).takeUnless { fallbackExpired },
            temperatureObservedAtMillis = prefs.getNullableLong(KEY_TEMPERATURE_AT).takeUnless { fallbackExpired },
            timezoneId = prefs.getString(KEY_TIMEZONE, null),
            refreshedAtMillis = refreshedAt.takeUnless { fallbackExpired },
            manualQuery = prefs.getString(KEY_MANUAL_QUERY, null),
            message = prefs.getString(KEY_MESSAGE, "Cached observation context") ?: "Cached observation context"
        )
        return when {
            fallbackExpired -> loaded.copy(
                status = ContextStatus.STALE,
                message = "Saved location is available, but weather is expired. Tap Refresh now or leave the app open briefly while online."
            )
            !loaded.isFresh -> loaded.copy(
                status = ContextStatus.STALE,
                message = "${loaded.weatherAgeLabel}. Stridulate will refresh automatically in the background."
            )
            else -> loaded
        }
    }

    private fun save(value: ObservationContext) {
        _state.value = value
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_MODE, value.mode.name)
            .putString(KEY_STATUS, value.status.name)
            .putString(KEY_LOCATION_LABEL, value.locationLabel)
            .putNullableDouble(KEY_LATITUDE, value.latitude)
            .putNullableDouble(KEY_LONGITUDE, value.longitude)
            .putString(KEY_REGION, value.region.name)
            .putNullableDouble(KEY_TEMPERATURE_F, value.temperatureF)
            .putNullableInt(KEY_HUMIDITY, value.humidityPercent)
            .putNullableBoolean(KEY_IS_DAYLIGHT, value.isDaylight)
            .putNullableLong(KEY_TEMPERATURE_AT, value.temperatureObservedAtMillis)
            .putString(KEY_TIMEZONE, value.timezoneId)
            .putNullableLong(KEY_REFRESHED_AT, value.refreshedAtMillis)
            .putString(KEY_MANUAL_QUERY, value.manualQuery)
            .putString(KEY_MESSAGE, value.message)
            .apply()
    }

    private fun roundCoordinate(value: Double): Double =
        String.format(Locale.US, "%.2f", value).toDouble()

    private fun android.content.SharedPreferences.getNullableDouble(key: String): Double? =
        if (contains(key)) java.lang.Double.longBitsToDouble(getLong(key, 0L)) else null

    private fun android.content.SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.getNullableInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.getNullableBoolean(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun android.content.SharedPreferences.Editor.putNullableDouble(
        key: String,
        value: Double?
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, java.lang.Double.doubleToRawLongBits(value))

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private fun android.content.SharedPreferences.Editor.putNullableInt(
        key: String,
        value: Int?
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putInt(key, value)

    private fun android.content.SharedPreferences.Editor.putNullableBoolean(
        key: String,
        value: Boolean?
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putBoolean(key, value)

    companion object {
        private const val PREFS_NAME = "observation_context_v2"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_STATUS = "status"
        private const val KEY_LOCATION_LABEL = "location_label"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_REGION = "region"
        private const val KEY_TEMPERATURE_F = "temperature_f"
        private const val KEY_HUMIDITY = "humidity_percent"
        private const val KEY_IS_DAYLIGHT = "is_daylight"
        private const val KEY_TEMPERATURE_AT = "temperature_at"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_REFRESHED_AT = "refreshed_at"
        private const val KEY_MANUAL_QUERY = "manual_query"
        private const val KEY_MESSAGE = "message"

        private const val RECENT_LOCATION_MAX_AGE_MILLIS = 10L * 60L * 1000L
        private const val LOCATION_TIMEOUT_MILLIS = 12_000L
        private const val NETWORK_TIMEOUT_MILLIS = 10_000L
    }
}
