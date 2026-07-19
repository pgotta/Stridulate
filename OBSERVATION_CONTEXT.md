# Observation context

Observation context is optional and never required for audio identification.

## Inputs

- **Device mode:** requests Android approximate location only. A user-requested refresh asks Android for a present-time fix rather than relying on an hours-old location.
- **Manual mode:** accepts a U.S. city or ZIP through Open-Meteo geocoding.
- **Current weather:** requests `temperature_2m`, `relative_humidity_2m`, and `is_day` from Open-Meteo.
- **Season/time:** derives month and season from the returned timezone. Open-Meteo `is_day` is preferred for day/night; the local clock is the offline fallback.

Coordinates from device location are rounded to two decimal places before storage or weather lookup. The user can turn context off, which clears its local preferences.

## Freshness and refresh behavior

- Weather under **10 minutes** old is considered fresh.
- At **10 minutes**, the app attempts an automatic location/weather refresh in an independent background coroutine.
- Pressing **Record** starts the microphone immediately and never waits for location, weather, GPS, or the network.
- A manual **Refresh now** action always bypasses the app cache.
- Temperature may participate in a sourced species profile for at most **30 minutes**. The app conservatively uses whichever is older: its successful fetch time or Open-Meteo's returned observation timestamp.
- A previous temperature may remain visible for up to **2 hours** only as an offline fallback for the same rounded coordinates.
- After 2 hours, temperature and humidity are discarded until a successful refresh.
- The UI shows both fetch age and provider-observation age when available.
- A failed refresh never changes the timestamp to make old weather appear fresh.

The result page can refresh current location/weather and rerank the retained full audio-model output. This does not rerun or alter the calibrated audio probabilities.

## Imported recordings

A saved or shared recording may have been made at another time or place. Before analyzing it, the app asks the user to choose:

- **Use current conditions** when it was recorded here and now.
- **Audio only** for older, downloaded, shared, or remotely recorded audio.

Audio-only import creates a disabled context snapshot even when the user's normal live-recording context is enabled.

## Ranking behavior

The calibrated audio model still controls acceptance and rejection. Unknown, minimum confidence, minimum Top-1/Top-2 margin, and critical recording-quality failures are evaluated from the original audio output before contextual reranking.

For the displayed Top 3, context applies bounded soft multipliers:

- active month or nearby season,
- broad region support,
- day/night behavior,
- sourced species temperature range, only while weather is no more than 30 minutes old.

The combined multiplier is clamped to 0.85–1.15. Context never excludes a species, and the percentages displayed remain the original calibrated audio probabilities.

Current temperature is fetched and displayed. No species temperature ranges are invented. The framework uses a range only when `context_profiles.json` supplies minimum, maximum, and source fields.

## Weather interpretation

Open-Meteo provides current modeled weather for rounded coordinates. It is not a thermometer at the insect. Temperature and humidity can differ within vegetation, near pavement, against buildings, at different elevations, and between sun and shade. Context is therefore deliberately secondary to audio.

## Privacy

- Audio stays on-device.
- Recording-quality assessment stays on-device.
- Context is opt-in.
- Approximate coordinates and cached weather are stored in `observation_context_v2` preferences.
- Those preferences are excluded from Android cloud backup and device transfer.
- Disabling context clears the stored values.
