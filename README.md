# Stridulate

> **Work in progress / proof of concept.** Stridulate is not yet a dependable field-identification product. It is published for experimentation, testing, and open development; expect false matches, conservative rejections, and uneven species coverage.

**Private, on-device insect sound identification for Android.**

Stridulate listens to recordings of crickets, katydids, cicadas, and related singing insects, then compares the audio with a focused Tier 1 TensorFlow Lite model. It displays the three closest supported matches, explains how reliable each class currently is, and can optionally use broad region, season, time of day, and current outdoor temperature as observation context.

> Stridulate is an identification aid, not a scientific guarantee. The current model supports 66 singing-insect species and should be expected to say **No confident match** when an insect is unsupported, unclear, distant, or masked by other sounds.

> The bundled epoch-19 model is research-only because its source pool includes noncommercial licenses.


## Screenshots

<p align="center">
  <img src="docs/screenshots/home.svg" alt="Stridulate home screen with optional observation context" width="30%" />
  <img src="docs/screenshots/field-guide.svg" alt="Stridulate searchable field guide" width="30%" />
  <img src="docs/screenshots/species-detail.svg" alt="Fall Field Cricket species detail and reference recording" width="30%" />
</p>
<p align="center">
  <img src="docs/screenshots/listening.svg" alt="Stridulate live listening and spectrogram screen" width="30%" />
  <img src="docs/screenshots/result.svg" alt="Stridulate possible-match result with reliability details" width="30%" />
</p>

From left to right: Home and observation context, searchable field guide, species details, live recording, and a model result with reliability information.

## Highlights

- Runs the audio classifier locally on the Android device.
- Uses the exact epoch-19 67-class TensorFlow Lite model and preprocessing frontend.
- Supports 66 insect classes plus `Unknown_or_unsupported`.
- Shows the **Top 3** supported matches instead of forcing one answer.
- Uses calibrated confidence and margin gates.
- Displays **Strong possible match**, **Possible match**, or **No confident match**.
- Labels classes as **Verified**, **Good**, or **Experimental**.
- Offers optional approximate location or manually entered U.S. city/ZIP context.
- Adds broad region, season, actual day/night status, current temperature, and humidity.
- Refreshes optional location/weather in the background after 10 minutes and never delays recording.
- Provides a visible **Refresh now** control and can rerank an existing result with refreshed context.
- Assesses recording quality locally, including clipping, signal strength, signal clarity, active-signal coverage, and possible overlapping callers.
- Treats imported recordings safely by asking whether to use current conditions or audio-only analysis.
- Keeps raw audio inference and recording-quality analysis on-device.
- Includes an expanded field guide with reliability and confirmation guidance.
- Saves unresolved recordings in a private local **Unknowns** archive.
- Shares the original lossless WAV to iNaturalist through Android's share sheet.
- Links a public iNaturalist observation and checks its latest community identification on demand.
- Opens a prefilled GitHub tracking issue and includes a daily public-status sync workflow.
- Requires explicit human review and CC BY 4.0 approval before exporting an original WAV for training.

## What users should expect

Stridulate is not yet a universal “Shazam for every insect in America.” It is a focused first-stage identifier for the species represented by the Tier 1 model.

It should perform best when:

- One supported insect is clearly dominant in the recording.
- The phone is reasonably close to the caller.
- The insect calls repeatedly for several seconds.
- Wind, traffic, voices, frogs, birds, and machinery are limited.
- The species and regional call variation are represented in training data.
- The predicted class has Verified or Good evaluation support.

It will struggle when:

- The insect is not one of the 66 supported classes.
- Multiple insects overlap in a dense chorus.
- The caller is distant or very quiet.
- Wind or human-made noise dominates the clip.
- Frogs, birds, cicadas, or mechanical sounds resemble a supported call.
- Closely related species produce nearly identical songs.
- Temperature substantially changes pulse timing or call structure.
- A regional call variant was absent from the training recordings.

Users in areas well represented by the training recordings may see stronger results. Users in the Southwest, Pacific Coast, Rocky Mountains, Gulf Coast, and other regions with many unsupported local species may see **No confident match** more often. That is intentional: the app should reject uncertain audio rather than confidently assign an unsupported insect to the nearest known class.

## How identification works

When a user records an insect, Stridulate:

1. Captures the audio and converts it to the model’s required format.
2. Resamples to 44.1 kHz when needed.
3. Creates the same 128-band mel spectrogram used during training.
4. Runs overlapping five-second windows through the TFLite model.
5. Mean-pools logits across the windows.
6. Applies model temperature calibration.
7. Measures recording quality and flags severe clipping, weak signal, heavy background noise, or possible overlapping callers.
8. Evaluates the top confidence, runner-up margin, Unknown/Unsupported class, and critical quality failures.
9. Optionally applies small contextual adjustments for region, season, and time.
10. Displays the three most plausible supported species.

The model input is FLOAT32 `[1, 128, 431, 1]`. The output is FLOAT32 `[1, 67]`. Output size is read from the model tensor at runtime rather than hardcoded.


## v2.3.1+ field safety overlay

The model's original 0.35 confidence and 0.08 margin calibration is preserved for provenance, but it is no longer sufficient to present a species candidate. Real phone recordings can make an incorrect known class receive a high softmax score. The runtime therefore applies stricter, precision-first acceptance rules:

- Verified: at least **0.85 confidence** and **0.25 margin**
- Good: at least **0.90 confidence** and **0.30 margin**
- Experimental: at least **0.93 confidence** and **0.35 margin**
- Greater Angle-wing temporary override: at least **0.95 confidence** and **0.40 margin**
- Not Ready: never accepted as primary
- Gross frequency, bandwidth, or rhythm conflict: rejected

A **Strong possible match** additionally requires a Verified class, at least 0.95 confidence, a 0.40 margin, Good recording quality, and a passing acoustic-profile check. It is still not a confirmed identification.

## Understanding results

### Strong possible match

Direct **Strong possible match** wording is reserved for a Verified species that passes both the calibrated confidence threshold and the winner-versus-runner-up margin threshold.

Example:

```text
Strong possible match: Common True Katydid
87% audio match
Reliability: Verified
```

This means the model has comparatively strong evaluation support for that class. It does not mean the identification is scientifically certain.

### Possible match

Good and Experimental classes use **Possible match**, even when their model percentage is high.

Not Ready classes are never accepted as the primary result; they can appear only as nearby alternatives under **No confident match**.

```text
Possible match: Carolina Ground Cricket
71% audio match
Reliability: Good
```

The percentage represents the model’s preference among its available classes. The reliability tier describes how much evaluation evidence supports trusting that class. A high Experimental percentage is not automatically more dependable than a somewhat lower Verified result.

### No confident match

The app shows **No confident match** when:

- `Unknown_or_unsupported` wins,
- the highest supported confidence is below the calibrated threshold,
- the margin over the second candidate is too small,
- a critical quality check finds that the clip is too short, extremely quiet, or severely clipped, or
- the recording does not provide a sufficiently clear answer.

The Top 3 may still appear as leads, but they are not presented as an identification.

Common causes include unsupported species, overlapping callers, distant audio, background noise, and closely related calls.

## Reliability tiers

The tiers describe support within this particular model release—not the biological certainty of an individual observation.

### Verified

The strongest classes in the locked Tier 1 evaluation. These are eligible for direct **Strong possible match** wording when the confidence and margin gates pass.

### Good

Classes with useful evaluation performance and a reasonable amount of supporting data, but not enough evidence for firm identification language. They are presented as **Possible match**.

### Experimental

Classes with limited recordings, inconsistent evaluation performance, challenging look-alikes, or incomplete geographic coverage. These are useful suggestions that should be checked carefully against the field guide and an independent reference recording.

The current release contains:

- 14 Verified classes
- 3 Good classes
- 32 Experimental classes
- 17 Not Ready classes

The tier assignments are stored in `app/src/main/assets/android_reliability.json`.

## Region, season, time, and current weather

Observation context is optional. A user may grant Android approximate-location permission or enter a U.S. city or ZIP manually.

When enabled, Stridulate can:

- request a present-time approximate location fix,
- map the observation to a broad U.S. region,
- determine the current month and season,
- use Open-Meteo `is_day` for actual local day/night context when available,
- fetch current outdoor temperature and relative humidity,
- apply small, bounded region/season/time adjustments when ordering close candidates,
- show both when Stridulate fetched the weather and the provider's observation age,
- refresh current location and weather on demand,
- rerank an existing result after a successful refresh.

Example:

```text
Approximate location · Northeast U.S.
74°F · 68% humidity · Summer · Night
Updated just now · Weather observation 4 minutes ago
```

### Freshness policy

Location/weather is optional and runs independently from audio capture. Pressing **Record** starts the microphone immediately; it never waits for GPS or the network.

The old two-hour normal cache has been replaced with a stricter policy:

- **Under 10 minutes:** considered fresh; no automatic network request is made.
- **10–30 minutes:** Stridulate attempts an automatic background refresh; the prior value may remain usable if refresh fails, and recording is never interrupted.
- **Over 30 minutes:** temperature is not used for species-specific scoring. The cutoff uses whichever is older: Stridulate's fetch time or the weather provider's observation timestamp.
- **Up to 2 hours:** retained only as an offline display fallback for the same rounded location.
- **Over 2 hours:** temperature and humidity are discarded until refreshed.

The Home screen includes **Refresh now**. Result screens can also request fresh current location/weather and rerank the Top 3 without rerunning the audio model.

Open-Meteo supplies current modeled conditions rather than a thermometer reading at the insect itself. Local vegetation, pavement, elevation, sun exposure, and other microclimates may differ.

### Live versus imported recordings

Current weather is appropriate for a recording made directly in the app. A saved file may have been captured at another time or place, so Stridulate asks:

- **Use current conditions** — only when the file was recorded here and now. This forces a fresh request; if context has never been enabled, Android asks for approximate-location permission first.
- **Audio only** — the safe default for older, downloaded, shared, or remotely recorded files.

This prevents July audio imported in January, or audio from another state, from being reranked using the phone's present conditions.

### Context does not overrule the audio

The calibrated audio result remains primary. Context may help reorder close Top 3 candidates, but it never hard-excludes a species and does not rewrite the displayed audio percentages.

The app should never claim that a species is impossible in a location. Ranges shift, seasons vary, individual insects occur outside typical ranges, and users may analyze recordings made elsewhere.

### Temperature behavior

Current temperature is fetched and displayed. It affects ranking only when a species has a sourced temperature profile and the weather snapshot is no more than 30 minutes old.

This is deliberate. Insect calls can change with temperature—including pulse rate, phrase timing, activity level, and sometimes frequency—but using temperature responsibly requires species-specific biological references or equations. The code is structured so those profiles can be added without inventing temperature rules.

Weather lookup failure does not prevent identification. Region, season, and local-time context can continue to work. A recent temperature may be shown as an offline fallback, but older weather is excluded from scoring.

## Automatic recording-quality assessment

Every live or imported waveform is checked locally for:

- duration,
- overall signal level,
- combined signal clarity (temporal contrast plus spectral structure),
- clipping,
- percentage of the clip containing an active signal,
- prominent low-frequency noise or speech,
- dominant-frequency stability,
- a conservative possible-overlap/chorus warning.

The result page reports **Good**, **Fair**, or **Poor** recording quality and shows the measured diagnostics. Extremely short, extremely quiet, or severely clipped audio can force **No confident match** even when the classifier produces a high score.

The overlap warning is advisory. It does not yet separate individual species into isolated audio tracks and should not be interpreted as proof that multiple species are present.

## Unknown recordings and community identification

A **No confident match** or **Possible match** does not have to become discarded data. Stridulate provides an explicit community-review loop:

1. Save the original lossless WAV and result metadata in the local **Unknowns** archive.
2. Share the WAV to iNaturalist and verify the original observation date and approximate location there.
3. Paste the resulting public iNaturalist observation URL back into Stridulate.
4. Check that observation on demand, or refresh all linked observations from the archive.
5. Optionally open a prefilled GitHub issue so the observation can be tracked publicly.
6. After community feedback arrives, listen again and perform a human label review.
7. The original recorder may approve their original local WAV under **CC BY 4.0** and export a WAV + JSON contribution ZIP.
8. A maintainer must still review the bundle before adding it to any training or evaluation set.

Stridulate does not automatically treat an iNaturalist community taxon as ground truth. It also does not download iNaturalist-hosted audio into the project dataset.

### GitHub status tracking

The repository includes a daily workflow that checks open issues labeled `inaturalist-tracking`. It reads the linked public observation metadata, updates one status comment, and changes `needs-id` to `community-id-broad` for a broad result and to `community-id-ready` only for a species-level result. It never closes the issue or approves the recording automatically.

Users can run an immediate check from **Actions → Refresh iNaturalist community IDs**.

### Why direct submission is not embedded yet

Creating an iNaturalist observation directly inside Stridulate requires a registered iNaturalist OAuth application, client ID, redirect URI, and user authorization flow. The current share-and-link workflow works today without collecting passwords, storing an app secret, or asking users to grant account write access. A future OAuth implementation can replace only the upload step while preserving the local archive and human-review safeguards.

See `COMMUNITY_IDENTIFICATION.md` for the complete maintainer and contributor workflow.

## Privacy

- Audio inference runs on-device.
- The recording is not uploaded for classification.
- Observation context is optional.
- Approximate coordinates are rounded before local storage.
- Cached location and weather context are excluded from Android backup and device transfer.
- Saved Unknowns WAV files and their linked-observation metadata are also excluded from backup and device transfer.
- Users can disable and clear context data.
- Weather and manual-place lookups require network access when used.

Review `OBSERVATION_CONTEXT.md` for implementation details.

## Supported classes

The exact ordered model labels are stored in:

```text
app/src/main/assets/labels.txt
```

The model contains 66 supported insect classes plus:

```text
Unknown_or_unsupported
```

The field-guide records and species mappings are stored in:

```text
app/src/main/assets/species.json
```

The app validates the model, label ordering, tensor shapes, metadata, normalization values, reliability mappings, and field-guide mappings at runtime or through the included verifier.

## Recording tips

For the strongest result:

1. Move closer without disturbing the insect.
2. Point the phone microphone toward the caller.
3. Record at least one complete repeated phrase.
4. Shield the microphone from wind.
5. Avoid speaking or moving during the sample.
6. Try to isolate one caller rather than a large chorus.
7. Record a second sample from another position when uncertain.
8. Compare the suggested call description and season with the field guide.

A clean individual caller is far more useful than a long recording dominated by wind or many overlapping insects.

## Field guide

Each species detail page can include:

- Common and scientific name
- Reliability tier
- Evaluation guidance
- Call description
- Typical listening season
- Habitat and broad range
- Region/season observation context
- Similar species or possible confusion
- Suggestions for confirming the identification

Field-guide content should be treated as educational guidance rather than a substitute for an expert determination or a voucher specimen.

## Project structure

```text
app/src/main/java/com/pgotta/stridulate/
├── audio/           Recording and audio preparation
├── classifier/      TFLite inference and exact model frontend
├── environment/     Optional location, season, time, and weather context
├── community/       Local unknown archive, iNaturalist links, sharing, and exports
├── data/            Species records, photos, and reliability data
└── ui/              Jetpack Compose screens and ViewModel

app/src/main/assets/
├── insect_model.tflite
├── labels.txt
├── model_meta.json
├── normalization.json
├── species.json
├── android_reliability.json
├── audit_manifest.json
└── context_profiles.json

tools/
└── sync_inaturalist_issues.py

verification/
├── verify_project.py
├── preprocessing_parity_report.json
└── FINAL_VERIFICATION.txt
```

## Building the app

Complete requirements and build instructions are in [`BUILD.md`](BUILD.md).

The repository intentionally contains **no Windows `.bat` files**. On Windows, build through Android Studio. GitHub Actions builds with the Unix `gradlew` wrapper and publishes the debug APK as a workflow artifact.

## Large model file

`app/src/main/assets/insect_model.tflite` is approximately 80 MB. It is below GitHub’s individual-file hard limit at the time this package was prepared, but it makes the repository relatively large.

For a long-lived public repository, consider one of these approaches:

- Keep the model directly in Git for the simplest developer setup.
- Track `*.tflite` through Git LFS.
- Publish the model as a versioned GitHub Release asset and add a verified download step.

The current package includes the model directly so the project is self-contained. Because the model is larger than GitHub’s browser-upload limit, upload this repository with GitHub Desktop or the Git command line rather than dragging all files into the GitHub website.

## Verification

Run the offline project verifier from the repository root:

```bash
python verification/verify_project.py
```

The verifier checks:

- exact model and metadata hashes,
- label ordering and class counts,
- TFLite flatbuffer tensor names, types, and shapes,
- dynamic output handling,
- calibration and rejection behavior,
- reliability tier mappings,
- Top 3 result behavior,
- location/context framework,
- privacy exclusions,
- local Unknowns archive and FileProvider boundaries,
- public iNaturalist linking and refresh behavior,
- GitHub community-identification tracking workflow,
- explicit human-review and CC BY 4.0 export safeguards,
- resampler and mel frontend contracts,
- Gradle wrapper structure.

See:

- `VERIFICATION.md`
- `TIER1_V5_INTEGRATION.md`
- `OBSERVATION_CONTEXT.md`
- `FIELD_GUIDE_AUDIT.md`
- `COMMUNITY_IDENTIFICATION.md`

The included verification report confirms targeted Kotlin checks for the environment repository, audio-quality logic, and ViewModel, plus an all-source Kotlin parser check and extremely close numerical parity with the training frontend. A full `assembleDebug` was attempted but could not complete in the packaging container because the Gradle distribution could not be downloaded and no Android SDK was available there. GitHub Actions is configured to perform the real Android build after upload.

## Testing priorities

Before treating the app as release-ready, test:

- multiple clean recordings for every Verified class,
- Good and Experimental classes across different recording sources,
- unsupported insects and non-insect sounds,
- wind, traffic, speech, frogs, birds, and machinery,
- single callers versus choruses,
- different Android phone microphones,
- native 44.1 kHz and resampled recordings,
- location permission denied, accepted, and later revoked,
- manual city/ZIP entry,
- non-blocking ten-minute background context refresh and the manual **Refresh now** action,
- 30-minute scoring cutoff and two-hour offline-display fallback,
- current-location refresh failure and stale-weather handling,
- imported-file **Use current conditions** versus **Audio only** behavior,
- recording-quality grades, clipping, weak audio, and possible-overlap warnings,
- saving, reopening, and deleting local Unknowns records,
- sharing WAV evidence through FileProvider,
- linking and refreshing a public iNaturalist observation,
- opening a prefilled GitHub tracking issue,
- human label approval and contribution ZIP export,
- recordings outside a species’ typical region or season,
- whether context improves close calls without overpowering the audio.

The most important release questions are:

- Does unsupported audio reliably produce **No confident match**?
- Is the correct species usually present in the Top 3?
- Are incorrect results being presented too confidently?
- Are Experimental results clearly communicated as uncertain?
- Do percentages remain calibrated rather than clustering unrealistically near 100%?

## Known limitations

- Only the Tier 1 label set is supported.
- Nationwide coverage is uneven because training coverage is uneven.
- Context profiles are broad and not a replacement for county-level range data.
- Temperature influences only species with sourced profiles and only while the weather snapshot is current enough.
- Open-Meteo conditions may differ from the insect's immediate microclimate.
- Recording-quality and overlap checks are signal-processing advisories, not expert acoustic diagnoses.
- Similar calls may be difficult to distinguish from phone audio alone.
- Dense choruses can cause unstable or blended predictions; source separation is not included.
- A model confidence percentage is not a biological probability of correctness.
- Field-guide text and range information should be reviewed continuously as coverage expands.
- iNaturalist sound identifications depend on human community activity and may remain broad or unresolved.
- GitHub issue synchronization mirrors public metadata only; it is not an expert verification service.
- Direct in-app iNaturalist account submission is not included until a registered OAuth application is configured.

## Roadmap

Potential next steps include:

- Expand high-quality recordings for weak and geographically underrepresented classes.
- Add additional regional Tier models rather than forcing one enormous national model.
- Add sourced species-specific temperature/pulse-rate profiles.
- Add recording playback and more detailed spectrogram review.
- Train and validate multi-label chorus identification using controlled synthetic mixtures.
- Investigate source separation only after a mixture/clean-source evaluation set exists.
- Add editable notes, corrected observation time, and corrected broad location to saved Unknowns.
- Add user-confirmed and expert-reviewed observations for evaluation through the included community workflow.
- Add direct iNaturalist OAuth submission after registering an application and completing security review.
- Improve unsupported-sound and multi-species rejection.
- Add confidence calibration reporting by class and region.
- Add automated Android instrumentation and device tests.
- Publish signed APKs through GitHub Releases.

## Contributing

Bug reports, public iNaturalist tracking links, original recordings with reviewed identifications, field-guide corrections, regional coverage research, and Android improvements are welcome. Please read `CONTRIBUTING.md` before opening a pull request.

Do not add copyrighted recordings or datasets without clear redistribution permission. Every training or evaluation contribution should document its source, license, species identification basis, location precision, date/season, and recording conditions whenever available.

## License

The original Stridulate source code and project documentation are licensed under the **MIT License**. See [`LICENSE`](LICENSE).

The MIT License permits use, copying, modification, distribution, sublicensing, and commercial use of the code.

The bundled model weights and third-party media are **not automatically relicensed under MIT**. Their separate terms are documented in [`MODEL_AND_ASSET_LICENSES.md`](MODEL_AND_ASSET_LICENSES.md). The current epoch-19 model remains research-only because its training pool includes noncommercially licensed source recordings.

## Disclaimer

Stridulate is an educational identification assistant. Results may be wrong and should not be used as the sole basis for scientific publication, regulatory decisions, ecological surveys, pesticide use, medical decisions, or claims about invasive or protected species.
