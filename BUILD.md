# Building Stridulate v3

Stridulate v3 is the final Stage-J Android consolidation: frozen J.1 / Perch 2.0 with 88 acoustic classes.

Windows `.bat` files are intentionally kept out of GitHub. The downloadable build/install package contains the Windows helper and the small frozen runtime support files it needs.

## Requirements

- Windows 11 for the recommended in-place phone workflow
- Android Studio or Android SDK 34
- JDK 17
- Python 3
- Android platform tools / `adb`
- Git with access to the private `pgotta/Stridulate` repository
- Internet access for the first Gradle/model download
- Android 8.0 / API 26 or newer

## Critical existing-phone rule

The application ID remains:

```text
com.pgotta.stridulate
```

If the phone already contains saved Unknown WAVs:

- **do not uninstall Stridulate**
- **do not clear app data**
- do not use `adb uninstall`

The recommended helper uses:

```text
adb install -r
```

If Android reports an incompatible signing certificate, the helper stops. It does not remove the old app or its data.

## Frozen runtime files

The following files are not committed to GitHub and are not packed into the APK:

```text
perch_v2_no_dft.onnx
j1_stage_d_affine.bin
j1_calibration.json
```

The Windows helper verifies and stages them into:

```text
/data/data/com.pgotta.stridulate/files/models/
```

through Android's `run-as` support on the debug build.

Expected Perch contract:

```text
file: perch_v2_no_dft.onnx
bytes: 413350933
sha256: 4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017
input: FLOAT32 [N,160000]
sample rate: 32000 Hz
window: 5 seconds
global embedding: 1536 values
```

Expected affine head:

```text
file: j1_stage_d_affine.bin
bytes: 541040
sha256: 066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a
shape: 1536 x 88 + 88 bias
```

Expected calibration:

```text
file: j1_calibration.json
sha256: d4a45f2902a48b49b584157c8c603f40ea99445e02ae623012e1ec27cd6dc75e
species: 88
```

The app verifies these contracts again before inference.

## Recommended Windows build + install

Use the downloadable package supplied with v3 and double-click:

```text
RUN_BUILD_AND_INSTALL_FINAL_ANDROID.bat
```

The helper is designed to:

1. log every major step;
2. clone/update the private GitHub source;
3. run `verification/verify_final_j_android.py`;
4. obtain the exact pinned Perch ONNX if it is not cached;
5. verify Perch, the J.1 affine head and the J.1 calibration by SHA-256;
6. build `:app:assembleDebug` locally;
7. run `verification/verify_final_j_apk.py`;
8. require one connected Android device;
9. install with `adb install -r` only;
10. stage all three frozen runtime files into app-private storage;
11. remove temporary device copies;
12. launch Stridulate.

The helper never uninstalls the app and never clears data.

## Android Studio build

You can still build the APK normally:

1. Clone/download the repository.
2. Open the repository root in Android Studio.
3. Use JDK 17.
4. Allow Gradle sync to finish.
5. Build the debug APK.

APK location:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A plain Android Studio APK build is not sufficient for first launch of v3 because the three frozen runtime files must also be staged into the app's private `files/models` directory. Use the Windows helper for the field-test phone.

## Command-line build

Windows:

```text
gradlew.bat --no-daemon :app:assembleDebug
```

macOS/Linux:

```bash
chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug
```

## Source verification

From the repository root:

```text
python verification/verify_final_j_android.py
```

This validates, among other contracts:

- exact 88-label order;
- app ID/version;
- ONNX Runtime version;
- expected Perch size/hash in source constants;
- expected affine/calibration hashes in source constants;
- sound-sensitivity default;
- result wording/navigation hooks;
- saved-Unknown reanalysis hook;
- runtime binaries are not accidentally committed into APK assets.

## APK verification

After building:

```text
python verification/verify_final_j_apk.py app/build/outputs/apk/debug/app-debug.apk
```

The verifier checks the frozen label assets and confirms that the private runtime binaries were not accidentally packaged into the APK.

## GitHub Actions

`.github/workflows/android-build.yml` performs:

1. source-contract verification;
2. JDK 17 setup;
3. Gradle debug build;
4. APK-contract verification;
5. Gradle log upload;
6. debug APK upload as a CI artifact.

The CI APK proves that the repository compiles. For an existing phone with irreplaceable app-private Unknown recordings, prefer the local Windows helper because the existing local debug signing identity is what allows a safe in-place update.

## Troubleshooting

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

Stop. Do **not** uninstall the existing app just to get past this message. The installed app is signed differently and uninstalling would remove app-private data. Use the same local build/signing environment that installed the existing Stridulate build.

### Model unavailable on Home

Run the Windows build/install helper again. It verifies and stages all three files into:

```text
files/models/
```

The classifier intentionally fails closed if any runtime file is missing or its checksum differs.

### More than one device shown by ADB

Disconnect extra devices/emulators and rerun the helper. It deliberately refuses to guess which phone should receive the build.

### Android SDK not found

Open Android Studio SDK Manager and install Android SDK 34 / platform tools. Do not commit `local.properties`.

### Java version error

Use Android Studio's bundled JDK 17 or another JDK 17 installation.

## Research boundary

Stage J is closed after v3. This build intentionally preserves the strong frozen dominant-caller detector instead of deploying the failed experimental multi-source branches. Future simultaneous-source work belongs to Stage K.
