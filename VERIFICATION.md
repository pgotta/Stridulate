# Verification

## Epoch-19 model assets

The included offline verifier checks:

- exact float32 TFLite SHA-256 and byte size,
- exact 67-label order and labels checksum,
- metadata, normalization, reliability and audit-manifest checksums,
- FLOAT32 input `[1,128,431,1]`,
- FLOAT32 output `[1,67]`,
- unknown class at index 66,
- temperature `0.8342779874801636`, confidence threshold `0.35`, and margin threshold `0.08`,
- best-checkpoint provenance: epoch 19, selection score `0.8666692989117276`,
- 14 Verified, 3 Good, 32 Experimental and 17 Not Ready supported classes,
- every model label maps to a field-guide entry,
- Not Ready and Unknown classes cannot become the primary result,
- dynamic output handling and mean-logit pooling,
- exact 44.1 kHz mel frontend settings,
- app version 2.3.2 / version code 12.

Run from the project root:

```text
python verification/verify_project.py
```

The same verifier also checks the existing observation-context, privacy, recording-quality, reference-playback, community-review, iNaturalist and GitHub-contribution workflows. It specifically verifies that recording starts before any stale-context refresh and that optional context refresh runs independently.

## Build verification

Open the project in Android Studio and allow Gradle sync to finish, then use **Build → Build APK(s)**. GitHub Actions also runs the verifier and builds a debug APK after each push. Custom Windows `.bat` helper files are intentionally excluded from the public repository.

This packaging environment could not run Gradle because external downloads and an Android SDK were unavailable. The offline project verifier and targeted Kotlin compilation checks passed before packaging.

## Model license

The bundled model is research-only because the training pool includes noncommercial source licenses. Do not use this model in a monetized release without a commercially compatible retraining pool.
