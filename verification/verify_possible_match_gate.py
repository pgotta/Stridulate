from pathlib import Path

root = Path(__file__).resolve().parents[1]
gate = (root / "app/src/main/java/com/pgotta/stridulate/audio/PossibleMatchGate.kt").read_text()
vm = (root / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text()
listen = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text()
qa = (root / "app/src/main/java/com/pgotta/stridulate/ui/components/TestFeedbackControls.kt").read_text()
repo = (root / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt").read_text()

checks = {
    "gate has balanced non-insect filter": "signature.insectLikelihood" in gate and "quality.blockingReason" in gate,
    "gate requires recurrence": "requiredConsecutiveWindows" in gate and "consecutiveWindows" in gate,
    "frozen J1 explicitly untouched": "does NOT change frozen J.1 acceptance" in gate,
    "live candidates filtered through gate": "PossibleMatchGate.allows" in vm,
    "raw top3 retained for QA": "candidates = rawTopThree" in vm,
    "gate slider exposed": "Possible-match gate" in listen and "STRICT" in listen and "SENSITIVE" in listen,
    "QA collapsed by default": "var expanded by rememberSaveable { mutableStateOf(false) }" in qa,
    "QA export captures gate": "possible_match_gate_level" in repo and "insect_likelihood" in repo,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("POSSIBLE MATCH GATE VERIFY FAIL: " + "; ".join(failed))
print("POSSIBLE MATCH GATE VERIFY PASS: noise/silence filter + recurrence + slider + collapsible QA + diagnostics")
