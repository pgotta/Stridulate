# Stridulate v0.3 QA test feedback

This beta-only test harness lets a tester label visible J.1 / Perch candidates while running known insect recordings, non-insect noise, or outdoor field tests.

## Recommended workflow

1. Open **Listen** or analyze a saved recording.
2. In **QA TEST FEEDBACK**, set the **Target** to the insect you are intentionally testing.
3. For wind, HVAC, speech, children, traffic, appliances, and other non-insect tests, set the Target to **Noise / non-insect**.
4. When the Top 3 appears, tap on the relevant visible candidate:
   - **Correct**: this candidate is the insect actually being tested.
   - **Incorrect**: this candidate is wrong. Set the target first so the log records what should have been present.
   - **Noise**: the input is noise/non-insect and this candidate is a false insect response.
5. Repeat with different recordings and real outdoor tests.
6. Tap **Export** when finished and share the generated `stridulate-test-feedback-*.zip` back for analysis.

## What every feedback tap records

- app/model version
- Correct / Incorrect / Noise verdict
- expected test target
- selected candidate
- complete visible Top 3
- calibrated J.1 score for each candidate
- each species' frozen J.1 acceptance threshold and gate status
- live rolling-window time range when applicable
- sound-sensitivity setting and analysis gain
- recording-quality diagnostics
- coarse region/weather/time context when available

The QA export deliberately omits exact GPS coordinates and does not automatically include audio. Existing raw/session WAV handling remains unchanged.

## Export contents

The shared ZIP contains:

- `feedback.jsonl` — lossless machine-readable events
- `feedback.csv` — flattened spreadsheet-friendly rows
- `README.txt` — export description

Feedback stays in private app storage across normal in-place app upgrades until **Clear** is explicitly confirmed.


## Live possible-match gate

The live Top 3 is no longer forced to display a species for every rolling window. A separate **Possible-match gate** filters weak/non-insect windows using acoustic insect-likelihood, recording quality, recurrence, and evidence strength. The slider runs from **STRICT** to **SENSITIVE** and does not alter the frozen J.1 accepted-call thresholds.

The QA panel is collapsed to one thin row by default. Tap the row to set a target, export, or clear feedback. QA exports now include the possible-match gate setting and acoustic diagnostics (insect-likelihood, peak frequency, tonality, low-frequency ratio, and peak stability).
