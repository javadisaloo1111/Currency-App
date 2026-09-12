# Release Guide (Android)

## Automated release (recommended)

1. Every push runs `Android CI` (`clean test assembleRelease`).
2. To publish a release, push a tag (or dispatch `Android Release` with a tag name):
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```
3. The `Android Release` workflow runs the full test suite, builds the signed APK,
   and creates a GitHub Release named `Gold Market Android <tag>` with two assets:
   `app-release-<tag>.apk` and `app-release-<tag>.apk.sha256` (SHA-256 checksum).
   The direct download URL is always:
   `https://github.com/javadisaloo1111/Currency-App/releases/download/<tag>/app-release-<tag>.apk`.

## Signing

The workflow signs with the repository release keystore when these **Actions secrets** exist:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of the PKCS#12 keystore (`base64 -w0 talayar-release.p12`) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `talayar`) |
| `ANDROID_KEY_PASSWORD` | key password |

If the secrets are absent, the workflow falls back to the **debug key** so the APK
remains installable, and prints a warning. Debug-signed releases cannot be upgraded
in place by a later properly-signed build (users must uninstall first), so configure
the secrets before public distribution.

### Generating a keystore

```bash
# generate a 30-year RSA key + PKCS#12 keystore (no Java required)
PASS=$(openssl rand -hex 16)
openssl req -x509 -newkey rsa:4096 -sha256 -nodes \
  -keyout key.pem -out cert.pem -days 10950 \
  -subj "/CN=Talayar Release/O=Talayar/C=IR"
openssl pkcs12 -export -out talayar-release.p12 \
  -inkey key.pem -in cert.pem -name talayar -passout pass:$PASS

# store the secrets
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 talayar-release.p12)
gh secret set ANDROID_KEYSTORE_PASSWORD --body "$PASS"
gh secret set ANDROID_KEY_ALIAS --body "talayar"
gh secret set ANDROID_KEY_PASSWORD --body "$PASS"
```

Keep `talayar-release.p12` and the password somewhere safe (password manager);
all future updates must be signed with the same key.

## Versioning

Bump `versionCode` / `versionName` in `android/app/build.gradle.kts`
(also `APP_VERSION_NAME` buildConfigField) before tagging.

## In-app updates (since v1.0.1)

The app checks `https://api.github.com/repos/javadisaloo1111/Currency-App/releases/latest`
on start (at most once every 12 hours; a manual «بررسی بروزرسانی» lives in Settings),
compares versions numerically (1.0.10 > 1.0.9, never downgrades), downloads the official
`app-release-<tag>.apk` asset, verifies size + ZIP magic + the published SHA-256 checksum
and opens the Android package installer (user confirmation is always required; the app
never installs silently).

**Force update (optional, off by default):** add a line like the one below anywhere in
the release notes (body) to make the update dialog non-dismissable for users on older
versions:

```
minimum_supported_version: 1.0.2
```

Rules the updater enforces:
- Only `https://github.com/javadisaloo1111/Currency-App/releases/download/...` URLs are accepted.
- A release without an APK asset, or with the service unreachable, is ignored silently —
  the app keeps working normally.
- Users see plain Persian copy («نسخه جدید آمده است» / «به‌روزرسانی» / «بعداً»); no
  technical jargon.
