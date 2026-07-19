# Epoch-19 67-class model integration

Stridulate v2.3.2 bundles the exact completed training export:

- `app/src/main/assets/insect_model.tflite`
- `app/src/main/assets/labels.txt`
- `app/src/main/assets/model_meta.json`
- `app/src/main/assets/normalization.json`
- `app/src/main/assets/android_reliability.json`
- `app/src/main/assets/audit_manifest.json`

## Model contract

- 66 supported singing-insect classes plus `Unknown_or_unsupported`
- FLOAT32 input `[1, 128, 431, 1]`
- FLOAT32 output `[1, 67]`
- 44.1 kHz, five-second windows, 50% overlap
- Mean-logit pooling across every window
- Temperature calibration `0.8342779874801636`
- Minimum confidence `0.35`
- Minimum top-one/top-two margin `0.08`

The output count is read from the TFLite tensor at runtime and checked against `labels.txt`; it is not hard-coded into inference.

## Reliability behavior

- **Verified:** shown as **Strong possible match**.
- **Good:** shown as **Possible match**.
- **Experimental:** shown as **Possible match — limited validation**.
- **Not Ready:** never accepted as the primary result; it may appear only among the closest alternatives beneath **No confident match**.
- **Unknown gate:** rejects unsupported, uncertain, and non-insect/background recordings.

Reliability tiers describe evaluation support for this model release. They do not establish scientific certainty.

## Release provenance

The best preserved checkpoint is epoch 19 with selection score `0.8666692989`. The float32 TFLite parity test passed. See `MODEL_EPOCH19_AUDIT.md` and `app/src/main/assets/audit_manifest.json`.

## License caution

The model is research-only because its training pool includes noncommercial source licenses. The Android source can be developed and tested, but this model should not be used in a monetized release without a commercially compatible retraining pool.
