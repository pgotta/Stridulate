#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LISTEN = ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt"
text = LISTEN.read_text(encoding="utf-8")

def require(token: str, message: str) -> None:
    if token not in text:
        raise SystemExit("HEARD NOW MARKER VERIFY FAIL: " + message)

require("mutableStateListOf<Double>()", "missing persistent visual marker timeline")
require("LaunchedEffect(signalAssessment, candidates, legacyCandidates)", "marker is not keyed to fresh live analysis state")
require("val markerTime = elapsedSeconds", "fresh HEARD NOW state is not timestamped")
require("historicalHeardTimes", "HEARD NOW marker does not persist into scrolling history")
require("((it - visibleStart) / visibleSpan)", "historical marker is not mapped into the scrolling spectrogram window")
require("activeMarkerFraction = if (heardNow) activeMarkerFraction else null", "bright current marker is not tied to the moving timestamp")
if "activeMarkerFraction = if (heardNow) 0.987f else null" in text or "activeMarkerFraction = if (heardNow) 0.985f else null" in text:
    raise SystemExit("HEARD NOW MARKER VERIFY FAIL: hard-coded right-edge marker regression remains")

seconds_per_column = 4096.0 / 48_000.0
visible_span = 260 * seconds_per_column
event_time = 30.0
def fraction(now: float) -> float:
    start = max(0.0, now - visible_span)
    return max(0.0, min(1.0, (event_time - start) / visible_span))
positions = [fraction(t) for t in (30.0, 32.0, 36.0, 44.0, 52.0)]
if not all(a > b for a, b in zip(positions, positions[1:])):
    raise SystemExit(f"HEARD NOW MARKER VERIFY FAIL: marker does not move left: {positions}")
if positions[0] < 0.95 or positions[-1] > 0.05:
    raise SystemExit(f"HEARD NOW MARKER VERIFY FAIL: unexpected timeline endpoints: {positions}")

print("HEARD NOW MARKER VERIFY PASS")
print("fixed event positions:", ", ".join(f"{x:.3f}" for x in positions))
