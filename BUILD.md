# Building Stridulate

Stridulate intentionally keeps all Windows `.bat` files out of the public repository.

## Requirements

- Android Studio
- Android SDK 34
- JDK 17
- Internet access during the first Gradle dependency download
- Android 8.0 / API 26 or newer for installation

## Recommended Windows build

1. Clone or download the repository.
2. Open the repository root in Android Studio.
3. Allow Gradle sync to finish.
4. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Find the APK at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

No `.bat` file is required.

## Run on a connected phone

1. Enable Developer options and USB debugging on the Android phone.
2. Connect the phone by USB.
3. Select the phone in Android Studio.
4. Run the `app` configuration.

## macOS or Linux command line

Make the Unix wrapper executable once:

```bash
chmod +x gradlew
```

Build:

```bash
./gradlew --no-daemon :app:assembleDebug
```

## GitHub Actions

The workflow at:

```text
.github/workflows/android-build.yml
```

does the following after a push to `main`:

1. Checks out the repository.
2. Runs `python verification/verify_project.py`.
3. Installs JDK 17.
4. Makes `gradlew` executable.
5. Builds the debug APK.
6. Uploads `app-debug.apk` as a workflow artifact.

To download the APK:

1. Open the repository on GitHub.
2. Select **Actions**.
3. Open the latest successful **Android build** run.
4. Download the `stridulate-debug-apk` artifact.

## Offline project verification

From the repository root:

```text
python verification/verify_project.py
```

This validates the model files, labels, metadata, tensor contract, reliability mappings, preprocessing contract, app version, and safety behavior.

## Common build issues

### `Permission denied: ./gradlew`

The GitHub workflow should contain:

```yaml
- name: Make Gradle wrapper executable
  run: chmod +x ./gradlew
```

### Android SDK not found

Open Android Studio's SDK Manager and install Android SDK 34. Android Studio will create the local SDK configuration for your machine.

Do not commit `local.properties`.

### Java version error

Configure Android Studio to use its bundled JDK 17, or install a separate JDK 17.

### Dependency download failure

The first build requires internet access so Gradle can download Android and Kotlin dependencies. Retry after confirming the connection and Gradle is not in offline mode.
