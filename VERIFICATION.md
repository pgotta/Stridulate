# Verification report

## Assets and model contract

- `insect_model.tflite` remains byte-for-byte identical to the supplied Tier 1 v5 model.
- `labels.txt`, `model_meta.json`, and `normalization.json` retain their exact V50 hashes.
- Direct FlatBuffer inspection confirms FLOAT32 input `mel` `[1,128,431,1]` and FLOAT32 output `logits` `[1,22]`.
- The Android runtime derives the output count from the output tensor and validates it against the 22 labels.
- No obsolete 66-class labels, hardcoded output count, or heuristic classifier fallback is active.

## Preprocessing parity

The production Kotlin `Fft`, `SincResampler`, and `MelSpectrogram` sources were compared numerically with the v5 torchaudio training frontend:

- Native 44.1 kHz: maximum normalized-mel error `0.0000423193`; correlation `0.9999999999966668`
- 48 kHz after sinc resampling: maximum normalized-mel error `0.000991881`; correlation `0.9999999998746673`

The machine-readable report is `verification/preprocessing_parity_report.json`.

## Identification behavior

The offline verifier checks:

- metadata temperature calibration,
- calibrated confidence and margin rejection,
- Unknown/Unsupported gating,
- Top 3 supported species,
- Verified / Good / Experimental mappings,
- direct **Identified** wording restricted to Verified classes,
- critical recording-quality rejection,
- unchanged display of calibrated audio percentages after context reranking,
- version name `2.2.1` and version code `9`.

## Current observation context

The v2.2.1 verification checks:

- optional coarse location only; no fine-location permission,
- manual U.S. city/ZIP lookup,
- present-time device-location request,
- Open-Meteo current temperature, humidity, day/night, timezone, and source timestamp parsing,
- separate fetch-age and provider-observation-age display, with the older age controlling temperature scoring,
- visible **Refresh now** and result-screen refresh controls,
- automatic pre-recording refresh after 10 minutes,
- explicit current-conditions analysis forcing a fresh request,
- 30-minute maximum age for temperature scoring,
- two-hour maximum display-only offline fallback,
- failure preserving the original fetch timestamp instead of making old weather look fresh,
- imported-recording audio-only safety and permission flow,
- soft bounded region/season/time reranking that never hard-excludes a species,
- temperature scoring only through sourced species profiles,
- rounded local coordinates and Android backup/device-transfer exclusions.

## Recording-quality assessment

The on-device quality assessor was compiled and smoke-tested with synthetic audio. Checks include:

- duration,
- RMS signal level,
- combined temporal/spectral clarity,
- clipping percentage,
- active-signal coverage,
- low-frequency contamination,
- dominant-frequency stability,
- conservative possible-overlap warning,
- blocking reasons for extremely short, extremely quiet, or severely clipped audio.

A clear five-second tone scored **Good / 100**, while a fully clipped waveform scored **Poor** and produced a blocking reason.

## Community identification and contribution loop

The v2.2.1 checks also cover:

- explicit local saving of the original analyzed audio as lossless mono WAV;
- Android FileProvider boundaries for WAV sharing and ZIP export;
- public iNaturalist observation linking and on-demand status refresh;
- a scheduled GitHub issue sync that reads public metadata only;
- separate unresolved, broad-community-ID, and species-level-ID tracking states;
- preservation of a human-approved state when public iNaturalist metadata is refreshed;
- explicit human label review, contributor credit, rights attestation, and CC BY 4.0 approval;
- WAV + JSON + README contribution bundle export using the recorder's local WAV, never downloaded iNaturalist media;
- local community recordings and metadata excluded from cloud backup and device transfer.

The workflow intentionally does not auto-create observations, auto-post identifications, accept community consensus as ground truth, or move media from iNaturalist into a training dataset.

## Kotlin and project checks

Completed in the packaging container:

- environment freshness test: PASS,
- `EnvironmentRepository.kt` targeted Kotlin compilation with minimal Android/JSON stubs: PASS,
- recording-quality core compilation and smoke test: PASS,
- `StridulateViewModel.kt` targeted Kotlin compilation with minimal dependency stubs: PASS,
- community record model targeted Kotlin compilation with JSON stubs: PASS,
- GitHub/iNaturalist rank and comment-rendering smoke tests: PASS,
- all production Kotlin files parsed together with no parser, unclosed syntax, or redeclaration errors: PASS,
- reference playback lifecycle checks for guide disposal, Android back, and activity backgrounding: PASS,
- JSON parsing: PASS,
- Android XML parsing: PASS,
- Gradle wrapper JAR structure and launch scripts: PASS,
- final ZIP integrity: recorded in `verification/FINAL_VERIFICATION.txt` after packaging.

A full Android dependency/type resolution requires Gradle, the Android SDK, AndroidX Compose, and TFLite dependencies. `./gradlew --no-daemon assembleDebug` was attempted, but the packaging container could not resolve `services.gradle.org` and had no installed Android SDK. The included GitHub Actions workflow runs the verifier and full Android build in GitHub's hosted environment.

## Re-run locally

```text
python verification/verify_project.py
./gradlew --no-daemon assembleDebug
```
