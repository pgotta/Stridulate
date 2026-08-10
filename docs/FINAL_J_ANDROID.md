# Final Stage-J Android Architecture

This document records the Android handoff after Stage J was closed.

## Production research detector

Stridulate v3 uses the frozen Stage J.1 dominant-caller detector:

```text
raw/decoded audio
  ↓
optional user Sound sensitivity analysis gain
  ↓
band-limited resample to 32 kHz mono
  ↓
5-second Perch 2.0 waveform windows
  ↓
1536-value global embeddings
  ↓
frozen 88-class Stage-D affine head
  ↓
frozen J.1 per-species calibration
  ↓
J.1 acceptance / long-session persistence policy
  ↓
High confidence / Likely match / No confident match
```

The raw evidence WAV is never rewritten by Sound sensitivity.

## Why this is the final Stage-J detector

The controlled Stage-J validation baseline for frozen J.1 was:

| Metric | Frozen J.1 |
|---|---:|
| Single-insect exact | 94.77% |
| Negative rejection | 60.00% |
| Pair all-true | 4.35% |
| Quiet-insect recall | 15.52% |
| Mean extra labels | 0.104 |

Later J branches exposed additional multi-source signal but could not preserve the dominant-caller accuracy/safety tradeoff. J-FINAL therefore selected frozen J.1 rather than deploying a worse experimental multi-source branch.

These numbers come from the controlled Stage-J research validation framework and are not a claim of equivalent accuracy on arbitrary field-phone recordings.

## Stage-J boundary

Stage J is closed. There is no planned J.8/J.9/J.10 chain.

True simultaneous multi-insect identification becomes **Stage K**, where a purpose-built acoustic-object/localization/separation system can be developed separately. Frozen J.1 remains available as a downstream species recognizer.

## Frozen runtime contract

### Perch

```text
file: perch_v2_no_dft.onnx
bytes: 413350933
sha256: 4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017
sample rate: 32000 Hz
input: FLOAT32 [N,160000]
global embedding: 1536
```

### Stage-D affine head

```text
file: j1_stage_d_affine.bin
bytes: 541040
sha256: 066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a
magic: STRJ1AF1
weights: FLOAT32 [1536,88]
bias: FLOAT32 [88]
```

### J.1 calibration

```text
file: j1_calibration.json
sha256: d4a45f2902a48b49b584157c8c603f40ea99445e02ae623012e1ec27cd6dc75e
species: 88
```

All three files are staged into app-private storage by the Windows build/install helper and verified again by the Android classifier.

## Android data compatibility

Application ID remains:

```text
com.pgotta.stridulate
```

Existing saved Unknown WAVs are app-private data. Do not uninstall the app or clear its data. The local helper uses an in-place `adb install -r` update and stops if the signing identity is incompatible.

## UX changes in v3

### Result screen

- common name is the primary title;
- scientific name is secondary when a separate English common name is bundled;
- primary result and Top 3 are tappable;
- each opens the exact field-guide entry;
- Back returns to the same result page and preserves its scroll position;
- wording is `High confidence`, `Likely match`, or `No confident match`.

### Sound sensitivity

- visible on the listening screen;
- default is OFF / 1.0×;
- adjustable up to 4.0× analysis gain;
- live spectrogram and neural analysis use the same gain setting;
- raw saved WAV remains unchanged.

### Unknowns

A saved Unknown includes a **Re-analyze with frozen J.1** action. Reanalysis:

- uses the saved WAV;
- is audio-only, avoiding current-weather/location distortion;
- analyzes up to the normal imported-audio limit;
- leaves the original Unknown record, WAV, notes, community status and linked iNaturalist observation untouched.

### 88-species guide coverage

The model's exact 88-label order is bundled. Existing detailed guide entries are reused. If a newly supported class lacks a detailed hand-authored entry, the app creates a deliberately conservative placeholder rather than inventing precise biological claims.

## Confidence interpretation

`High confidence` is a conservative UI evidence band layered on an already accepted J.1 candidate. It is not a scientific certainty percentage.

`Likely match` means the species crossed its frozen J.1 acceptance threshold.

`No confident match` means no supported candidate crossed its active evidence gate, or a critical recording-quality blocker rejected the result.

## What v3 deliberately does not claim

- It does not claim to identify every insect in a chorus.
- It does not treat a high score as guaranteed biological certainty.
- It does not claim that current location/weather belongs to an imported historical recording.
- It does not fabricate detailed field-guide facts for newly added classes.
- It does not modify original evidence WAVs when sensitivity is increased.

## Next stage

Stage K should begin from this frozen Android baseline, not by changing J.1. Stage K can train and evaluate a dedicated localization/separation model while v3 remains the stable dominant-caller application branch.
