# Model and third-party asset licensing

## Source code and project documentation

Original Stridulate source code and original project documentation are licensed under the MIT License in `LICENSE`.

## Bundled model

The file:

```text
app/src/main/assets/insect_model.tflite
```

is not covered by the MIT License.

The current epoch-19 model was trained using a pool that includes material under noncommercial Creative Commons terms. It should therefore remain marked **research-only / noncommercial** unless it is replaced with a model trained entirely from material whose licenses permit the intended use.

Applying MIT to the application source does not override licenses or rights attached to training recordings or model inputs.

## Photos, reference audio, datasets, and other third-party material

Third-party photos, audio, datasets, names, metadata, and other bundled material remain subject to their original licenses and attribution requirements.

Where a file contains its own attribution or license information, that file-specific notice controls.

## Making the entire repository commercially reusable

To make the whole application—including its model—commercially reusable:

1. Build a new training pool using public-domain, CC0, CC BY, or explicitly permissioned recordings.
2. Preserve attribution where required.
3. Retrain and evaluate a replacement model.
4. Replace the current TFLite model and update its audit metadata.
5. Confirm that every bundled photo, reference recording, and dataset also permits commercial redistribution.

This notice is informational and is not legal advice.
