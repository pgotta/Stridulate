# v0.3 compact live tuning UI

The Listen screen keeps the spectrogram and live candidates visible while tuning.

- `GAIN` is a narrow vertical analysis-gain rail beside the spectrogram.
- `GATE` is a narrow vertical insect/noise-gate rail beside the spectrogram.
- The current top candidate is highlighted with `HEARD NOW` while the current cached window passes the gate.
- A bright amber right-edge marker on the spectrogram uses the same current-window heard state.
- Moving `GATE` immediately re-evaluates the cached current window without rerunning Perch/J.1. Sensitive can reveal a weak candidate immediately; Strict can hide it immediately.
- Frozen J.1 scores, thresholds, front-gate architecture and QA logging remain unchanged.
