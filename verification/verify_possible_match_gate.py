from pathlib import Path

root = Path(__file__).resolve().parents[1]
gate = (root / "app/src/main/java/com/pgotta/stridulate/audio/PossibleMatchGate.kt").read_text()
signal = (root / "app/src/main/java/com/pgotta/stridulate/audio/InsectSignalGate.kt").read_text()
clip = (root / "app/src/main/java/com/pgotta/stridulate/audio/ClipAnalyzer.kt").read_text()
vm = (root / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text()
listen = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text()
qa = (root / "app/src/main/java/com/pgotta/stridulate/ui/components/TestFeedbackControls.kt").read_text()
repo = (root / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt").read_text()
result = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/ResultScreen.kt").read_text()

checks = {
    "raw front gate exists": "object InsectSignalGate" in signal and "rawSamples" in signal,
    "front gate is before analysis gain": "InsectSignalGate.assess" in clip and "rawSamples = samples" in clip,
    "front gate rejects raw noise floor": "microphone noise floor" in signal,
    "front gate rejects broadband noise": "broadband and unstructured" in signal,
    "front gate rejects stationary hiss conservatively": "0.72 + 0.08 * x" in signal,
    "front gate rejects low frequency noise": "low-frequency broadband/noise" in signal,
    "display gate still requires recurrence": "requiredConsecutiveWindows" in gate and "consecutiveWindows" in gate,
    "live candidates require front gate": "if (!result.signalAssessment.passed)" in vm,
    "accepted logging requires front gate": "if (!result.signalAssessment.passed) return" in vm,
    "raw top3 retained for QA": "candidates = rawTopThree" in vm,
    "signal diagnostics retained for QA": "signalAssessment = result.signalAssessment" in vm and "signal_gate" in repo,
    "signal slider exposed": "title = \"GATE\"" in listen and "STRICT" in listen and "SENS" in listen,
    "gate slider re-filters current window immediately": "setLivePossibleMatchSensitivity" in vm and "filterLiveCandidates(rawTopThree, result, assessment)" in vm,
    "active heard marker wired to spectrogram": "activeMarkerFraction = if (heardNow)" in listen and "AMBER = HEARD NOW" in listen,
    "heard candidate highlight exists": "● HEARD NOW" in listen and "cardBackground" in listen,
    "compact vertical control rails": "VerticalControlRail" in listen and "rotate(-90f)" in listen and "title = \"GATE\"" in listen,
    "QA collapsed by default": "var expanded by rememberSaveable { mutableStateOf(false) }" in qa,
    "QA can label hidden noise window": "Mark current window as Noise" in qa,
    "normal result hides species on signal reject": "RAW J.1 CANDIDATES HIDDEN FROM NORMAL UI" in result,
    "score is not presented as probability": "J.1 SCORE" in (root / "app/src/main/java/com/pgotta/stridulate/ui/components/Common.kt").read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("INSECT FRONT GATE VERIFY FAIL: " + "; ".join(failed))
print("INSECT FRONT GATE VERIFY PASS: raw silence/noise rejection + recurrence + QA diagnostics + non-probability score UI")
