#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VM = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/pgotta/stridulate/MainActivity.kt").read_text(encoding="utf-8")
LISTEN = (ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text(encoding="utf-8")

def require(text: str, token: str, message: str) -> None:
    if token not in text:
        raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: " + message)

for token, msg in [
    ("pendingLiveFeedbackSnapshot", "missing frozen pending QA snapshot"),
    ("reviewedLiveTopLabel", "missing same-event post-review suppression"),
    ("latchLiveReviewIfAvailable", "missing live review latching helper"),
    ("_liveHeardNow = MutableStateFlow(false)", "current HEARD NOW state is not separated from latched review cards"),
    ("_liveFeedbackAck = MutableStateFlow<String?>(null)", "missing visible feedback acknowledgement state"),
    ("pendingLiveFeedbackSnapshot ?: lastLiveFeedbackSnapshot", "feedback is not tied to the frozen visible review window"),
    ("_liveCandidates.value = emptyList()", "QA verdict does not clear the reviewed result"),
    ('FeedbackVerdict.CORRECT -> "✓ Correct saved"', "Correct tap has no acknowledgement"),
    ('FeedbackVerdict.INCORRECT -> "✕ Incorrect saved"', "Incorrect tap has no acknowledgement"),
    ('FeedbackVerdict.NOISE -> "Noise saved"', "Noise tap has no acknowledgement"),
]:
    require(VM, token, msg)

for token, msg in [
    ("val liveHeardNow by vm.liveHeardNow.collectAsState()", "MainActivity does not observe current HEARD NOW state"),
    ("val liveFeedbackAck by vm.liveFeedbackAck.collectAsState()", "MainActivity does not observe QA acknowledgement"),
    ("heardNow = liveHeardNow", "ListenScreen does not receive current HEARD NOW state"),
    ("feedbackAck = liveFeedbackAck", "ListenScreen does not receive QA acknowledgement"),
]:
    require(MAIN, token, msg)

for token, msg in [
    ("heardNow: Boolean", "ListenScreen still derives current HEARD NOW from persistent result cards"),
    ("feedbackAck: String?", "ListenScreen cannot show feedback acknowledgement"),
    ("val hasPendingReview = candidates.isNotEmpty()", "visible review state is not separated from current sound state"),
    ("if (!hasPendingReview)", "result cards still disappear when HEARD NOW turns false"),
    ("LAST HEARD · stays here until you judge it", "latched result does not tell tester it is awaiting a verdict"),
    ("This result stays here until you tap Correct / Incorrect / Noise.", "QA persistence contract missing from UI"),
]:
    require(LISTEN, token, msg)

if "_liveCandidates.value = filterLiveCandidates(rawTopThree, result, result.signalAssessment)" in VM:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: fresh analysis still overwrites the latched result card")
if "val heardNow = signalAssessment?.passed == true && (candidates.isNotEmpty() || legacyCandidates.isNotEmpty())" in LISTEN:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: HEARD NOW still depends on latched candidate presence")
if "if (!heardNow)" in LISTEN:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: result visibility still depends on current HEARD NOW")

# Behavioral regression simulation: one qualifying event latches, remains through
# later non-qualifying windows, clears only on verdict, and does not immediately
# relatch the same continuous top label until the event ends.
pending = None
reviewed = None
visible = []

def update(passed: bool, top: str | None):
    global pending, reviewed, visible
    if not passed:
        reviewed = None
    elif top is not None and reviewed is not None and reviewed != top:
        reviewed = None
    if pending is None and not visible and top is not None and top != reviewed:
        pending = top
        visible = [top]

update(True, "tree_cricket")
if visible != ["tree_cricket"]:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: qualifying result did not latch")
update(False, None)
if visible != ["tree_cricket"]:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: result disappeared when sound stopped")
reviewed = visible[0]
pending = None
visible = []
update(True, "tree_cricket")
if visible:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: same continuous result immediately reappeared after verdict")
update(False, None)
update(True, "tree_cricket")
if visible != ["tree_cricket"]:
    raise SystemExit("LIVE REVIEW LATCH VERIFY FAIL: reviewed label did not become eligible after event ended")

print("LIVE REVIEW LATCH VERIFY PASS")
print("result persists until Correct / Incorrect / Noise; verdict clears and acknowledges")
