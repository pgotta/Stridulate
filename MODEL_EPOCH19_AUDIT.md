# Stridulate Final Training Audit

## Verdict

**Training, evaluation, calibration, and the float32 TFLite export completed successfully. Do not retrain this run.**

- Training log completed **26 epochs**.
- Best checkpoint: **epoch 19**, selection score **0.866669**.
- The final epoch scored **0.865668**, only 0.001001 below the winner.
- This is a stable plateau, not a late-training collapse.
- The float32 TFLite model passed parity with finite outputs, identical top-1 prediction, and maximum logit error 7.15e-06.
- The failed float16 candidate must not be used.

## Primary scientific results

| Evaluation set | Raw accuracy | Macro F1, all 67 | Supported macro F1 | Top-3 accuracy | Unknown raw F1 | Final unknown gate F1 |
|---|---:|---:|---:|---:|---:|---:|
| Validation/calibration | 88.4% | 72.7% | 77.5% | 96.0% | 90.4% | not derivable without validation predictions |
| Diagnostic test | 85.2% | 70.8% | 76.4% | 94.6% | 89.7% | 89.7% |
| Locked holdout | 89.1% | 70.6% | 74.4% | 96.7% | 92.6% | 91.5% |

The independent supplemental 21-species test macro F1 is **0.8948** (89.5%); recording-weighted F1 is **0.9203**.

## Supplemental species changes

- Improved: **14** species
- Unchanged: **1** species
- Regressed: **6** species
- Reliability-status regressions: **0**
- Newly moved to Verified: **Gryllus pennsylvanicus** and **Velarifictorus micado**

### Regressions

| Species | Base F1 | New F1 | Change | Test recordings | Independent sessions | Tier |
|---|---:|---:|---:|---:|---:|---|
| Platypedia_minor | 1.000 | 0.875 | -0.125 | 8 | 7 | EXPERIMENTAL |
| Oecanthus_californicus | 1.000 | 0.889 | -0.111 | 5 | 3 | EXPERIMENTAL |
| Allonemobius_fasciatus | 0.941 | 0.875 | -0.066 | 9 | 7 | EXPERIMENTAL |
| Neoconocephalus_nebrascensis | 0.833 | 0.788 | -0.045 | 17 | 14 | GOOD |
| Neoconocephalus_ensiger | 0.939 | 0.894 | -0.045 | 25 | 15 | VERIFIED |
| Neotibicen_superbus | 0.893 | 0.881 | -0.012 | 29 | 28 | VERIFIED |

The three largest numeric regressions are all based on only 3–7 independent sessions and remain Experimental. The better-supported regressions are small and did not change their reliability tier.

## Overfitting assessment

- Training loss continued to decline while the selection score oscillated in a narrow band after epoch 18.
- Epoch 19 scored 0.866669; epoch 26 still scored 0.865668.
- Locked-holdout supported macro F1 is 0.7439, about 0.0309 below validation.
- Supplemental independent macro F1 is 0.8948, below the epoch-19 supplemental validation F1 of 0.9518, but still strong.

**Conclusion:** mild validation optimism and ordinary class-specific trade-offs are present, but there is no evidence of catastrophic overfitting or a poisoned run.

## Android decision rules

1. Use the **float32** model only: `81037632` bytes.
2. Compute the exact 44.1 kHz, five-second mel input described in `model_meta.json`.
3. Average logits across all 50%-overlapping windows.
4. Divide averaged logits by temperature **0.834277987**, then apply softmax.
5. Return **No confident match** when top-1 is `Unknown_or_unsupported`, confidence is below **0.35**, or top-1 minus top-2 is below **0.08**.
6. Apply the species tier:
   - Verified → `Strong possible match`
   - Good → `Possible match`
   - Experimental → `Possible match — limited validation`
   - Not Ready → never use as the primary result; keep only in alternatives/debug output

Tier counts across the 66 supported species: **14 Verified, 3 Good, 32 Experimental, 17 Not Ready**.

### Not Ready classes

Allonemobius_allardi, Diceroprocta_viridifascia, Diceroprocta_vitripennis, Gryllus_assimilis, Magicicada_septendecula, Magicicada_tredecassini, Magicicada_tredecim, Neotibicen_latifasciatus, Neoxabea_bipunctata, Oecanthus_nigricornis, Oecanthus_quadripunctatus, Okanagana_bella, Okanagana_canescens, Okanagana_occidentalis, Okanagana_triangulata, Orchelimum_gladiator, Scudderia_septentrionalis

## Remaining cautions

- The single external Columbian trig challenge clip was not identified correctly. The calibrated gate safely returned Unknown rather than asserting the wrong species.
- The result bundle does not explicitly write `best_epoch=19` into the original `model_meta.json`; that provenance is recorded in this bundle's `audit_manifest.json` and `android_reliability.json`.
- The included training data and resulting model are explicitly research-only because the pool includes CC-BY-NC and CC-BY-NC-SA material. Do not monetize or treat it as commercially distributable without a commercial-safe retraining dataset.

## Exact next action

Do not run either training BAT again. Extract this bundle and double-click `0_VERIFY_AND_STAGE_MODEL.bat`. It verifies the exact hashes and stages the Android assets at:

`C:\Stridulate-Training\Android-Model-Staging`

The next code task is to patch the latest Android project so it reads these assets and applies the gate/tier rules. That requires the current Android project ZIP; copying the model alone is not enough.