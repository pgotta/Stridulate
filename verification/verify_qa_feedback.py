#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "QA repository": ROOT / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt",
    "QA share helper": ROOT / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackShare.kt",
    "QA Compose controls": ROOT / "app/src/main/java/com/pgotta/stridulate/ui/components/TestFeedbackControls.kt",
    "QA documentation": ROOT / "QA_TEST_FEEDBACK.md",
}
missing = [name for name, path in checks.items() if not path.is_file()]
if missing:
    raise SystemExit("QA VERIFY FAIL: missing " + ", ".join(missing))

repo = checks["QA repository"].read_text(encoding="utf-8")
controls = checks["QA Compose controls"].read_text(encoding="utf-8")
vm = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text(encoding="utf-8")
listen = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text(encoding="utf-8")
result = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/ResultScreen.kt").read_text(encoding="utf-8")
paths = (ROOT / "app/src/main/res/xml/file_paths.xml").read_text(encoding="utf-8")

required = [
    (repo, "enum class FeedbackVerdict { CORRECT, INCORRECT, NOISE }"),
    (repo, '"feedback.jsonl"'),
    (repo, '"feedback.csv"'),
    (repo, '"top3"'),
    (repo, '"threshold"'),
    (repo, '"gate_passed"'),
    (repo, '"analysis_gain"'),
    (repo, '"expected_target"'),
    (controls, "CandidateFeedbackButtons"),
    (controls, "Correct"),
    (controls, "Incorrect"),
    (controls, "Noise"),
    (vm, "recordLiveTestFeedback"),
    (vm, "recordResultTestFeedback"),
    (listen, "TestFeedbackPanel"),
    (result, "TestFeedbackPanel"),
    (paths, "test_feedback_exports"),
]
for text, needle in required:
    if needle not in text:
        raise SystemExit(f"QA VERIFY FAIL: missing contract: {needle}")

# Exact GPS must not be written into QA event/export JSON.
for forbidden in ['.put("latitude"', '.put("longitude"']:
    if forbidden in repo:
        raise SystemExit(f"QA VERIFY FAIL: privacy regression: {forbidden}")

print("STRIDULATE QA FEEDBACK VERIFY PASS: target selector + Correct/Incorrect/Noise + JSONL/CSV ZIP export")
