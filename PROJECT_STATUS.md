# Project status

Stridulate is a **work-in-progress proof of concept**, not a finished identification product.

## What currently works

- Offline Android inference with a 67-output TensorFlow Lite model.
- Top-three candidates with conservative open-set rejection.
- Reliability tiers and explicit Not Ready classes.
- Optional region/season/weather context that never blocks recording.
- Local saving and community-review workflow for unresolved recordings.

## Important limitations

- Species performance is uneven and many classes remain Experimental or Not Ready.
- High model similarity is not a probability that the identification is correct.
- Closely related callers, mixed choruses, unsupported species, speaker playback, distant audio, and phone processing can produce false matches.
- The bundled model is research-only because its training pool includes noncommercial licenses.
- The app is best treated as a field-testing and data-collection prototype.

## Near-term direction

The highest-value work is targeted field testing, saving real failure cases, improving common Northeastern species, expanding hard-negative insect audio, and validating each change against a fixed regression set.
