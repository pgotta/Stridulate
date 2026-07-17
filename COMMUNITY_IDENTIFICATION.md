# Community identification workflow

Stridulate 2.2.1 keeps unresolved recordings useful without pretending the model knows more than it does.

## User flow

1. Record or import a sound and receive **No confident match** or **Possible match**.
2. Tap **Save recording for community review**. Stridulate stores the original lossless WAV and result metadata locally.
3. Open **Unknowns**, select the record, and tap **Share WAV to iNaturalist**.
4. In iNaturalist, verify the original observation date and approximate location and upload the sound with a broad taxon when necessary.
5. Copy the public iNaturalist observation URL into Stridulate.
6. Tap **Check latest ID** at any time, or **Check linked IDs** from the Unknowns archive.
7. Optionally tap **Track this ID on GitHub**. This opens a prefilled public issue containing the Stridulate record ID and iNaturalist URL.
8. After community feedback arrives, listen again and perform the explicit human review in Stridulate.
9. The original recorder may approve their original local WAV under **CC BY 4.0** and export a WAV + JSON contribution ZIP.
10. Attach the ZIP to the GitHub issue. A maintainer must still review it before adding it to a dataset.

## Why submission is shared rather than fully embedded

Directly creating observations from Stridulate would require registering an iNaturalist OAuth application, configuring its client ID and redirect URI, and implementing a user authorization flow. The current share/link approach works without embedding an app secret or asking Stridulate users for their iNaturalist password.

A future OAuth integration can replace the share step after the project has a registered iNaturalist application. The local archive, public observation link, human approval, and contribution-bundle steps can remain unchanged.

## GitHub status synchronization

Issues labeled `inaturalist-tracking` are checked daily by `.github/workflows/community-identification-sync.yml`.

The workflow:

- extracts the public iNaturalist observation URL from each issue;
- fetches current public observation metadata;
- creates or updates one status comment;
- adds `needs-id` while no community taxon exists;
- changes that to `community-id-broad` when only a broad community taxon exists;
- changes that to `community-id-ready` only when the community taxon is species-level or finer;
- never downloads iNaturalist media;
- never marks an observation as approved training data;
- never closes the issue automatically.

Run it immediately from **Actions → Refresh iNaturalist community IDs → Run workflow**.

The Android button currently opens issues in `https://github.com/pgotta/Stridulate`. Forks or renamed repositories should update `CommunityShare.DEFAULT_GITHUB_REPOSITORY_URL`.

## Licensing and data boundaries

The training contribution contains the recorder's original local WAV, not a downloaded copy of the iNaturalist media. Approval requires a contributor name, a rights attestation, and an explicit **CC BY 4.0** contribution license.

iNaturalist's community taxon is supporting evidence. It is not treated as ground truth automatically. Maintainers should review the sound, the observation discussion, date/location plausibility, and confusion species before accepting it.

Do not put precise private coordinates, account credentials, tokens, or private observations into GitHub issues.
