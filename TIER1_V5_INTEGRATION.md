# Tier 1 v5 integration

The app bundles the exact supplied model and V50 runtime assets:

- `app/src/main/assets/insect_model.tflite`
- `app/src/main/assets/labels.txt`
- `app/src/main/assets/model_meta.json`
- `app/src/main/assets/normalization.json`
- `app/src/main/assets/species_reliability.json`
- `app/src/main/assets/context_profiles.json`

At startup the app validates:

- labels SHA-256 from v5 metadata,
- TFLite input/output dtypes and shapes,
- output element count derived from the actual output tensor,
- Unknown label and index,
- normalization values,
- reliability and field-guide mappings for every supported label.

## Reliability tiers

- **Verified:** explicitly marked VERIFIED in the V50 locked-holdout evaluation.
- **Good:** not V50-verified, but locked-holdout F1 is at least 0.80 with at least eight recordings.
- **Experimental:** remaining supported classes with more limited or uneven evaluation evidence.
- **Unknown gate:** rejects unsupported or uncertain recordings.

Only Verified species can produce the app's direct **Identified** wording. Good and Experimental species produce **Possible match** when the calibrated confidence and margin gates pass.

These tiers describe this model evaluation only and do not establish scientific certainty.
