# Contributing to Stridulate

Thank you for helping improve Stridulate. Contributions are most useful when they improve identification quality without overstating certainty.

## Before contributing

- Search existing issues before opening a duplicate.
- Keep pull requests focused on one clear change.
- Explain user-visible behavior and any scientific assumptions.
- Do not silently change model labels, preprocessing, calibration, or thresholds.
- Do not add copyrighted recordings, images, or datasets without redistribution rights.

## Android changes

1. Create a branch from the current default branch.
2. Make the smallest practical change.
3. Run `python verification/verify_project.py`.
4. Run `./gradlew assembleDebug` when an Android SDK is available.
5. Test permission-denied and offline behavior when touching environment context.
6. Include screenshots for visible UI changes.
7. Describe any remaining untested conditions in the pull request.

## Model or preprocessing changes

A model update must include:

- model provenance and version,
- ordered labels,
- exact preprocessing metadata,
- normalization values,
- input and output tensor contracts,
- calibration temperature and rejection thresholds,
- per-class evaluation metrics,
- unknown/unsupported evaluation,
- numerical parity testing for the Android frontend,
- updated reliability tiers and documentation.

Do not replace the model without also updating and validating all coupled assets.

## Recording or dataset contributions

Document as much of the following as possible:

- common and scientific identification,
- how the identification was confirmed,
- date and approximate location,
- temperature when known,
- time of day,
- recording device,
- whether the clip contains one caller or a chorus,
- source and redistribution license,
- edits, filtering, or resampling applied to the recording.

Avoid publishing precise coordinates for sensitive species or private property.

## Field-guide corrections

Cite a reliable biological source for changes to ranges, seasons, habitat, call descriptions, or temperature behavior. Clearly distinguish broad tendencies from strict rules.

## Pull-request checklist

- [ ] The project verifier passes.
- [ ] The Android project builds, or the reason it could not be built is documented.
- [ ] User-facing text avoids presenting model output as certainty.
- [ ] New data and media have compatible licensing.
- [ ] Privacy behavior remains clear and optional.
- [ ] Documentation is updated.

## Community-identified recordings

Use the app's **Unknowns** workflow or the `Track an iNaturalist sound identification` issue template.

A candidate recording is not ready for model use merely because iNaturalist displays a community taxon. A contribution must include:

- the Stridulate record ID;
- the public iNaturalist observation URL;
- the original recorder's local WAV, not a downloaded iNaturalist media copy;
- a reviewed scientific label;
- contributor credit;
- explicit CC BY 4.0 approval for that original WAV;
- enough date, broad location, and recording context to evaluate plausibility without exposing a private precise location.

Attach the app-generated contribution ZIP to the tracking issue. Maintainers should review the audio, community discussion, confusion species, and metadata before accepting it into training or evaluation data.

The scheduled GitHub workflow mirrors public iNaturalist metadata only. It does not establish ground truth and must not be used to scrape iNaturalist audio.
