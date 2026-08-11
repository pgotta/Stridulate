# Project status

Stridulate **v0.3** is a pre-release research build, not a finished identification product.

## What currently works

- Offline Android inference using frozen Stage J.1 / Perch 2.0.
- 88 supported acoustic classes with frozen per-species calibration and acceptance thresholds.
- Top-three candidates and explicit `High confidence`, `Likely match`, and `No confident match` result states.
- Sound sensitivity control for quiet callers, OFF by default, without altering the saved raw WAV.
- Optional region/season/weather context that remains secondary to the audio model.
- Persistent Log and Unknowns workflows.
- Non-destructive re-analysis of saved Unknown WAVs with the current frozen J.1 detector.
- Field-guide navigation for all 88 model labels.

## Important limitations

- v0.3 is still very beta and should be treated as a field-testing/research build.
- Controlled Stage-J testing showed strong single-dominant-caller performance, but those results do not imply equivalent accuracy on arbitrary wild phone recordings.
- Negative/background rejection is imperfect, so unsupported sounds can still produce false matches.
- Quiet callers remain substantially harder than clear dominant callers.
- Simultaneous multi-insect separation is not reliable; v0.3 is a dominant-caller identifier rather than a Merlin-style chorus separator.
- High model evidence is not biological certainty. Field-guide comparison and human review remain important.

## Near-term direction

Stage J is closed around the frozen J.1 Android baseline. The next research direction is Stage K: purpose-built acoustic-object localization/separation for simultaneous callers, while preserving v0.3/J.1 as the stable downstream dominant-caller recognizer.
