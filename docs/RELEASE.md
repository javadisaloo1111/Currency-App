# Release Guide (Android)

## Automated release (recommended)

1. Every push runs `Android CI` (`clean test assembleRelease`).
2. To publish a release, push a tag (or dispatch `Android Release` with a tag name):
   ```bash
   git tag v1.0.3
   git push origin v1.0.3
   ```
3. The `Android Release` workflow runs the full test suite, builds the signed APK,
   and creates a GitHub Release named `Gold Market Android <tag>` with two assets:
   `app-release-<tag>.apk` and `app-release-<tag>.apk.sha256` (SHA-256 checksum), and
   finally republishes the static `update.json` mirror the in-app updater falls back to.
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

`android/app/build.gradle.kts` declares the published version once, at the top of
the file — the single source of truth:

```kotlin
val appVersionCode = 4
val appVersionName = "1.0.3"
```

Bump **both** before tagging. `appVersionName` feeds `versionName` *and* the
`APP_VERSION_NAME` buildConfigField, which is what the in-app updater reports as
the installed version, so the manifest and the updater can no longer drift apart.

## Release verification

`Android CI` and `Android Release` both run `.github/scripts/verify-apk.sh`
against the APK they just built:

- ZIP/APK magic and a size above the 500 KB floor the in-app downloader enforces;
- `applicationId` / `versionName` / `versionCode` read back **from the package**
  (aapt2 → aapt → apkanalyzer → AGP merged manifest) and compared with the tag and
  the gradle values — the release job fails if the tagged APK is not the tagged version;
- the home-screen greeting must really be shipped inside the APK;
- SHA-256 of the artifact.

The release workflow then downloads the published asset back through its public
release URL and fails on a SHA-256 mismatch. Versions, checksum and the direct
download link are embedded in the release notes, so every release documents what
was actually published.

## In-app updates (since v1.0.1, hardened in v1.0.3)

### Where release metadata comes from

The checker reads one payload shape from an ordered list of sources and fails over
to the next source whenever one fails:

| # | Source | Notes |
|---|---|---|
| 1 | `https://api.github.com/repos/javadisaloo1111/Currency-App/releases/latest` | authoritative; GitHub excludes drafts/prereleases |
| 2 | `https://javadisaloo1111.github.io/Currency-App/update.json` | static mirror (GitHub Pages, `gh-pages`) |
| 3 | `https://raw.githubusercontent.com/javadisaloo1111/Currency-App/gh-pages/update.json` | static mirror, second host |
| 4 | `https://cdn.jsdelivr.net/gh/javadisaloo1111/Currency-App@gh-pages/update.json` | static mirror, third host (CDN) |

`update.json` is published by the release workflow step
**“Publish the static update mirror (update.json on gh-pages)”** and has exactly the
shape of the GitHub release object (`tag_name`, `draft`, `prerelease`, `assets[]`
with `browser_download_url` / `size` / `content_type`), so one DTO parses both and
the mirrors are drop-in replacements. If that step ever fails it only warns — the
release is already published and the API source still works.

Why this matters: `api.github.com` is a single host that is (a) frequently
unreachable on filtered networks and (b) limited to 60 anonymous requests per hour
**per IP**, which carrier-grade NAT makes easy to exhaust — the API then answers
`403` while `github.com` and the release CDN keep working fine. That combination is
what made v1.0.2 tell a user with working internet «شما متصل نیستید» /
«بروزرسانی ناموفق بود». This is the same multi-host failover the price snapshot
already uses.

Adding a private gateway or another mirror later means adding one URL to
`UpdateEndpoints.MIRRORS` in `data/update/UpdateSources.kt`; the checker, the asset
selection, the downloader, the installer and the UI do not change.

### What one check does

```
CHECKING → read release metadata (bounded retry per source, then failover)
         → reject drafts/prereleases and unparsable tags
         → numeric semver compare (1.0.10 > 1.0.9)
              equal or older → NoUpdate        («آخرین نسخه را دارید»)
              newer          → pick the installable asset → Available
         → any failure        → Error(kind)    (precise Persian reason)
```

Budget: at most 2 attempts per source with linear backoff (700 ms), and a 45 s
ceiling for the whole check, so a black-holed host can never hang the button.

### Failure taxonomy (`UpdateErrorKind`)

Every failure is distinct — there is no umbrella «failed», and a timeout can never
be rendered as «you are up to date».

| Kind | Shown to the user | Retry |
|---|---|---|
| `NO_INTERNET` | اتصال اینترنت برقرار نیست… | no |
| `DNS_FAILURE` | آدرس سرور بروزرسانی پیدا نشد… | yes |
| `TIMEOUT` | پاسخ سرور بروزرسانی طولانی شد… | yes |
| `TLS_FAILURE` | ارتباط امن با سرور بروزرسانی برقرار نشد… | yes |
| `CONNECTION_FAILED` | ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید. | yes |
| `RATE_LIMITED` (403/429) | سرور بروزرسانی موقتاً درخواست‌ها را محدود کرده است… | yes |
| `NOT_FOUND` (404) | نسخهٔ منتشرشده‌ای در سرور بروزرسانی پیدا نشد. | no |
| `SERVER_ERROR` (5xx) | سرور بروزرسانی موقتاً در دسترس نیست… | yes |
| `HTTP_ERROR` (other) | سرور بروزرسانی پاسخ ناموفق داد… | yes |
| `INVALID_RESPONSE` | پاسخ سرور بروزرسانی قابل خواندن نیست… | no |
| `RELEASE_NOT_FOUND` | انتشار معتبری در سرور بروزرسانی پیدا نشد. | no |
| `APK_NOT_FOUND` | نسخهٔ جدید پیدا شد، ولی فایل نصب آن منتشر نشده است. | no |
| `DOWNLOAD_FAILED` | دانلود بروزرسانی ناموفق بود… | yes |
| `INCOMPLETE_DOWNLOAD` | دانلود بروزرسانی کامل نشد… | yes |
| `CHECKSUM_MISMATCH` | فایل دانلودشده سالم نیست… | yes |
| `INVALID_APK` | فایل دانلودشده یک بستهٔ نصبی معتبر نیست… | yes |
| `INSTALL_FAILED` | نصب‌کنندهٔ اندروید باز نشد… | yes |
| `UNKNOWN` | بروزرسانی ناموفق بود… | yes |

The device-online answer comes from `NetworkMonitor` (probed with a 1.5 s ceiling,
optimistic on timeout): an `IOException` **while the device demonstrably has a
network** is `CONNECTION_FAILED`, not `NO_INTERNET`. Technical detail and the
original throwable are kept for logcat only — never rendered.

### Download and pre-install verification

- The download uses its **own OkHttp client with no overall call timeout** (the
  shared 25 s `callTimeout` used to cancel an 8.7 MB download on any link slower
  than ~350 kB/s). Per-socket timeouts stay bounded: 20 s connect, 30 s read/write.
- Response status must be 2xx and the `Content-Type` must not be `text/html`
  (a captive-portal/filtering page is rejected as `INVALID_APK`).
- Bytes stream into `cacheDir/updates/<name>.apk.part`; the file is renamed only
  after every check passes, and the partial file is deleted on every failure path
  and on cancellation. A size floor (500 KB) plus the published asset size guard
  against truncated transfers.
- `ApkVerifier`: existence, non-empty, published size, ZIP/APK magic and the
  published SHA-256 — read from the `.sha256` sidecar asset, falling back to the
  `digest` the release service publishes for the asset itself (`sha256:<64 hex>`;
  the `update.json` mirror carries it too). A digest that is not exactly 64 hex
  characters is ignored rather than trusted. When neither is published the size,
  magic, package and versionCode checks still apply.
- `ApkPackageInspector`: `PackageManager` must be able to read the archive, it must
  be **this** package, and its `versionCode` must not be older than the installed
  one (no downgrade through the updater).
- `ApkInstaller`: `content://` URI from `FileProvider`
  (`${applicationId}.fileprovider`, `res/xml/file_paths.xml` → `cacheDir/updates`)
  with `FLAG_GRANT_READ_URI_PERMISSION` — never `file://` — and the system
  installer, so the user always confirms. There is no silent install path.
- Missing «install unknown apps» permission opens the system settings page and the
  dialog offers «رفتن به تنظیمات» / «نصب» instead of failing.

### Cache semantics

- Automatic checks run at most once every 12 hours; «بررسی بروزرسانی» in Settings
  always bypasses the cache.
- A skipped automatic check returns `NotDueYet`, which is **not** `NoUpdate`: the UI
  must never claim «آخرین نسخه را دارید» for a check that did not happen.
- Only a definitive answer (`NoUpdate`, `Available`, `APK_NOT_FOUND`) records the
  check time. A failed check is not cached, so the next automatic check retries.
- Update failures never touch the price cache and never crash the app: the
  automatic check on start logs and stays silent, and the dialog only reports
  failures the user asked for.

**Force update (optional, off by default):** add a line like the one below anywhere
in the release notes (body) to make the update dialog non-dismissable for users on
older versions:

```
minimum_supported_version: 1.0.2
```

Rules the updater enforces:
- Only `https://github.com/javadisaloo1111/Currency-App/releases/download/...` URLs
  are accepted for the package and for its checksum sidecar.
- Assets that are not installable (`.sha256`, `.txt`, `.zip`, source archives,
  `*-debug.apk`, `*-unsigned.apk`) can never be selected; the choice is scored and
  deterministic regardless of asset order.
- A release without an installable asset is reported as `APK_NOT_FOUND` — it is
  never silently treated as “no update available”.
- Users see plain Persian copy («نسخه جدید آمده است» / «به‌روزرسانی» / «بعداً»); no
  technical jargon, no raw exceptions.
