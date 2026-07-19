# v2.3.1 Field Safety Update

## Why this update exists

A Columbian Trig recording played through a speaker and recorded by a phone produced a 72% Greater Angle-wing candidate. Both species are present in the 67-class model, so this exposed a real open-set / real-device confusion that the clean evaluation did not capture.

The neural model and 44.1 kHz preprocessing are unchanged. v2.3.1 adds a precision-first runtime overlay so a model percentage is not treated as identification certainty.

## Runtime acceptance rules

| Reliability tier | Minimum score | Minimum lead over runner-up |
|---|---:|---:|
| Verified | 85% | 25 points |
| Good | 90% | 30 points |
| Experimental | 93% | 35 points |
| Not Ready | Never accepted | Never accepted |

Greater Angle-wing temporarily requires **95%** with a **40-point** lead because of the documented confusion.

The strongest UI state is now **Strong possible match**, never Likely match. It additionally requires a Verified class, 95% score, 40-point lead, Good recording quality, and a passing acoustic sanity check.

## Acoustic sanity check

The app now rejects gross conflicts between the candidate and the measured recording:

- dominant frequency far outside a tolerant expected range,
- narrowband audio for a species expected to have a broad tick, buzz, or rasp,
- unusually broad audio for a tonal species, or
- a gross pulse-rate/rhythm conflict when reference data exists.

This check may reject a candidate. It can never promote one.

## Evaluation impact

The threshold-only overlay was replayed against the saved diagnostic and locked-holdout prediction files. Acoustic-profile rejection was not included in these numbers, so the table is conservative about its additional effect.

| Set | Old accepted accuracy | v2.3.1 accepted accuracy | Old supported coverage | v2.3.1 supported coverage | Unknown clips falsely accepted |
|---|---:|---:|---:|---:|---:|
| Diagnostic test | 83.7% overall / 90.9% supported | 94.3% overall / 95.8% supported | 87.2% | 72.1% | 88 → 13 |
| Locked holdout | 89.6% overall / 92.1% supported | 95.3% overall / 96.0% supported | 94.5% | 80.6% | 37 → 9 |

The tradeoff is intentional: fewer accepted results, substantially fewer confident errors.

## Known limitation

This is a safety correction, not a replacement for hard-negative retraining. More phone-recorded Columbian Trig, Greater Angle-wing, mixed chorus, and unsupported Massachusetts insect recordings are still needed for a genuinely stronger future model.
