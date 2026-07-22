#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
policy = json.loads((ROOT / "app/src/main/assets/android_reliability.json").read_text())["open_set_safety_gate"]

def accepted(label, tier, confidence, runner_up):
    rule = policy.get("species_overrides", {}).get(label) or policy["tier_rules"][tier]
    return confidence >= rule["minimum_confidence"] and confidence - runner_up >= rule["minimum_top1_top2_margin"]

assert not accepted("Microcentrum_rhombifolium", "VERIFIED", 0.72, 0.10), "72% Greater Angle-wing must be rejected"
assert accepted("Microcentrum_rhombifolium", "VERIFIED", 0.96, 0.10), "Very strong Greater Angle-wing candidate should pass threshold layer"
assert not accepted("Cyrtoxipha_columbiana", "EXPERIMENTAL", 0.92, 0.10), "Experimental class below 93% must be rejected"
assert accepted("Cyrtoxipha_columbiana", "EXPERIMENTAL", 0.95, 0.20), "High-score Experimental candidate should pass threshold layer"
print("PASS: v2.4.0 open-set gate scenarios")
