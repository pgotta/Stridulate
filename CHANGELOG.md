# Changelog

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
