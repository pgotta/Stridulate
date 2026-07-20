# Changelog

## 2.3.2 — GitHub proof-of-concept bundle and non-blocking context

- Added five current Android screenshots to `docs/screenshots` and a README gallery for GitHub.
- Marked the repository clearly as a work-in-progress proof of concept rather than a finished identification product.
- Removed the pre-record location/weather wait: pressing Record now starts the microphone immediately.
- Moved optional device/manual context refresh to an independent background scheduler.
- Background checks run once per minute but perform location/weather I/O only when the ten-minute freshness window has expired.
- Automatic device refresh reuses a recent coarse location when available; **Refresh now** still requests a fresh fix.
- Manual city/ZIP context reuses saved coordinates instead of geocoding the same place every ten minutes.
- Added verification checks that prevent a future regression to blocking pre-record refresh.
- Bumped Android version to 2.3.2 (version code 12).
## 2.3.1 — Precision-first field safety update

- Removed direct “Likely match” language; even the strongest result is now **Strong possible match**.
- Added a precision-first open-set safety overlay above the original model calibration.
- Verified classes now require at least 0.85 confidence and a 0.25 margin; Good requires 0.90/0.30; Experimental requires 0.93/0.35.
- Added a temporary Greater Angle-wing override requiring 0.95 confidence and a 0.40 margin after a documented Columbian Trig confusion.
- Added conservative frequency, bandwidth and rhythm sanity checks that may reject a neural-model candidate but never promote one.
- Corrected the Columbian Trig field-guide profile to approximately 7 kHz and updated the Greater Angle-wing call description/profile.
- Clarified that displayed percentages are model preferences among available classes, not probability of correctness.
- Kept the epoch-19 model, 44.1 kHz mel frontend and original calibration untouched.

## 2.3.0 — Epoch-19 67-class integration

- Integrated the completed 67-output float32 TFLite model and exact labels/metadata.
- Added the calibrated 0.35 confidence and 0.08 margin gate from the release evaluation.
- Added 66-class reliability data, including the Not Ready safety tier.
- Not Ready classes can no longer become the primary result.
- Updated result wording to Likely match / Possible match / No confident match.
- Preserved the proven 44.1 kHz mel frontend and dynamic output handling.
- Added exact asset hashes and the epoch-19 audit manifest.

## 2.2.1

- Fixed reference insect audio continuing after leaving a species detail page.
- Reference playback now stops when the guide is disposed, when Android back is handled, and when the app leaves the foreground.
- Pending online reference-audio lookups are also cancelled so they cannot begin playing after navigation.
- Bumped Android version to 2.2.1 (version code 9).

## 2.2

- Added a private local **Unknowns** archive for unresolved and lower-confidence recordings.
- Preserved the original analyzed audio as a lossless mono WAV when the user explicitly saves it.
- Added Android FileProvider sharing for WAV identification evidence and reviewed contribution ZIPs.
- Added a prepared iNaturalist share description containing the Stridulate record ID, observation context, and non-confirmatory Top 3 possibilities.
- Added public iNaturalist observation linking and on-demand community-identification refresh.
- Added **Check all linked observations** for the local archive.
- Added a prefilled **Track this ID on GitHub** issue flow with no embedded GitHub token.
- Added a daily GitHub Actions workflow that mirrors public iNaturalist status into tracking issues.
- Added separate `needs-id`, `community-id-broad`, and `community-id-ready` labels so a broad taxon is not mistaken for a known species; the workflow never closes or approves an issue automatically.
- Added a human review step requiring a reviewed label, contributor credit, rights attestation, and explicit **CC BY 4.0** approval for the recorder's original local WAV.
- Added WAV + JSON training-contribution ZIP export; iNaturalist-hosted media is never downloaded into the dataset.
- Excluded saved community WAVs and metadata from Android cloud backup and device transfer.
- Added `COMMUNITY_IDENTIFICATION.md`, an issue template, sync script, and verification checks.
- Bumped Android version to 2.2 (version code 8).

## 2.1

- Replaced the normal two-hour weather cache with a 10-minute automatic refresh window.
- Added a visible **Refresh now** control that bypasses cached location and weather.
- Added result-screen weather refresh and context reranking without changing audio probabilities.
- Added a 30-minute maximum age for temperature-based species scoring.
- Retained up to two hours only as an offline display fallback, then expires weather completely.
- Fixed failed weather refreshes so they no longer make old data appear newly refreshed.
- Added current relative humidity and Open-Meteo day/night status.
- Added separate fetch-age and provider-observation-age reporting; temperature scoring uses the older age.
- Added safe imported-recording choices: current conditions or audio-only analysis.
- Added on-device recording-quality assessment for duration, signal level, signal clarity, clipping, active-signal coverage, low-frequency noise, stability, and possible overlapping callers.
- Added Good/Fair/Poor quality reporting and critical-quality rejection.
- Updated README, observation-context documentation, verification checks, and UI wording.
- Bumped Android version to 2.1 (version code 7).

## 2.0

- Replaced the classifier with the Tier 1 v5 TensorFlow Lite model.
- Replaced labels, model metadata, and normalization assets.
- Matched Android preprocessing to the training frontend.
- Added dynamic output tensor handling.
- Added calibrated confidence and runner-up margin rejection.
- Added Verified, Good, and Experimental reliability tiers.
- Added Top 3 supported matches.
- Added Identified, Possible match, and No confident match result states.
- Added optional approximate location and manual U.S. city/ZIP context.
- Added broad region, season, time-of-day, and outdoor temperature context.
- Added a framework for sourced species-specific temperature profiles.
- Improved result presentation, home screen, guide browsing, and species details.
- Added privacy exclusions for local observation context.
- Added offline verification scripts and parity reporting.
