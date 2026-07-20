# Apply these files manually

No GitHub changes were made by this bundle.

## 1. Copy the replacements

Copy the contents of this ZIP into the root of your local `Stridulate` repository and allow it to replace existing files.

New files:

- `BUILD.md`
- `LICENSE`
- `MODEL_AND_ASSET_LICENSES.md`

Replacement files:

- `.gitignore`
- `.gitattributes`
- `README.md`
- `START_HERE.txt`
- `VERIFICATION.md`
- `verification/verify_project.py`

## 2. Delete every BAT file already tracked by Git

`.gitignore` prevents new BAT files from being added, but it does not remove files already committed.

At minimum, delete:

```text
gradlew.bat
```

Also delete any other file ending in `.bat`, including an old `BUILD_DEBUG_APK.bat` if it appears in your local checkout or repository history.

In GitHub Desktop, the deletions should appear under **Changes** after you delete the files locally.

## 3. Review before committing

Expected changes:

- all `.bat` files deleted,
- `BUILD.md` added,
- MIT `LICENSE` added,
- model/asset licensing notice added,
- verifier no longer requires `gradlew.bat`,
- README and supporting documents no longer instruct users to run BAT files.

Suggested commit message:

```text
Remove BAT files, add BUILD.md, and use MIT license
```

## 4. Push and check Actions

After pushing:

1. Open **Actions** on GitHub.
2. Open the newest **Android build** run.
3. Confirm the verifier passes.
4. Confirm the debug APK artifact is produced.
