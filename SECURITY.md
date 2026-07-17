# Security and privacy reports

Please do not publish a security or privacy vulnerability as a public issue before maintainers have had a reasonable opportunity to review it.

Because no private reporting address has been configured yet, open a minimal public issue that says a private security report is needed without including exploit details, private user data, precise coordinates, credentials, or secrets.

Relevant concerns include:

- unintended audio upload or retention,
- exposure of precise location,
- insecure local storage,
- embedded API keys or credentials,
- unsafe network transport,
- dependency vulnerabilities,
- backup or device-transfer leakage of observation context.

## Community-identification boundaries

- The current iNaturalist integration reads only public observation metadata and uses Android's user-controlled share sheet for sound submission.
- No iNaturalist password, OAuth token, GitHub token, or API secret is embedded in the Android app.
- GitHub Actions uses the repository-provided `GITHUB_TOKEN` only to update issues in the same repository.
- Direct iNaturalist submission must not be enabled until a registered OAuth application and PKCE authorization flow are reviewed.
- Never place private coordinates, private observations, credentials, or tokens in public tracking issues.
