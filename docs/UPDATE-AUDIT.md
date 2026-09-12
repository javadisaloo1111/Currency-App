# In-app update channel — audit, fixes and release handoff (v1.0.3)

Status of this document: the work below is **committed locally on
`arena/01a094fb-currency-app`** but has **not been compiled, unit-tested on a
JVM, or published** — the session that wrote it had no Android SDK/JDK, no access
to the build repositories and (after its earlier pull request was merged) no
GitHub push access. See [§6 Handoff](#6-handoff--how-to-publish-v103) for the
exact remaining steps.

---

## 1. Reported symptom

On a real device running **1.0.1**:

| When | Result |
|---|---|
| Before v1.0.2 existed | «بررسی بروزرسانی» → «شما آخرین نسخه را دارید» ✅ correct |
| After v1.0.2 was published | «شما متصل نیستید» / «بروزرسانی ناموفق بود» ❌ wrong |

…while the same phone, on the same network, browsed GitHub and downloaded
`app-release-v1.0.2.apk` manually without any problem.

## 2. Root cause

The updater had a **single point of failure and a single error message**:

1. `UpdateRepositoryImpl` called exactly one host — `api.github.com` — and wrapped
   *every* throwable in one generic `UpdateCheckResult.Failed`, which the UI
   rendered as «بروزرسانی ناموفق بود» (and the market code path renders any
   `IOException` as «no internet», hence «شما متصل نیستید»).
2. On restricted Iranian networks `api.github.com` is commonly DNS/SNI-filtered or
   reset, **and** it rate-limits unauthenticated calls to 60/hour **per IP** —
   carrier-grade NAT makes that trivial to exhaust, after which the API answers
   `403`. Meanwhile `github.com` and `objects.githubusercontent.com` (the release
   CDN) stay reachable, which is exactly why the manual download worked.
   → The failure was **transport**, not logic: the check never saw a payload.
3. A second, independent defect guaranteed that even a successful check would fail
   at the download step: the shared `OkHttpClient` set `callTimeout(25s)`, which
   caps the *whole* call. An 8.7 MB APK on a link slower than ~350 kB/s was
   cancelled mid-stream — ordinary on mobile data.

Secondary findings from the audit:

| Area | Finding |
|---|---|
| Retry | none (a single transient failure ended the check) |
| Error copy | one string for ~18 different failures |
| Offline vs. unreachable | indistinguishable (connectivity monitor not consulted by the updater) |
| Asset selection | name-prefix based; a release whose only asset was `.sha256` produced `NoUpdate` — a **false “you are up to date”** |
| Verification | size + ZIP magic + SHA-256 only; `PackageManager` was never asked whether the file is a readable package of *this* app and not older than the installed one |
| Cache | a skipped periodic check returned `NoUpdate` → «آخرین نسخه را دارید» without any network call |
| Manifest / NSC / FileProvider | ✅ already correct: `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `cleartextTrafficPermitted=false`, `${applicationId}.fileprovider` → `cache-path/updates` |
| TLS | ✅ no bypass anywhere (no custom `TrustManager`, no `hostnameVerifier`) |

## 3. What changed, per subsystem

| Subsystem | File(s) | Change |
|---|---|---|
| Error taxonomy | `domain/model/UpdateError.kt` (new) | 18 `UpdateErrorKind`s, each with its own plain-Persian `userMessage` and a `retryable` flag; `UpdateError` keeps detail + cause for logcat only |
| Result contract | `domain/repository/UpdateRepository.kt` | `NoUpdate` / `Available` / **`NotDueYet`** / **`Error(UpdateError)`** — a skipped check and a failed check can no longer masquerade as “up to date” |
| Sources & failover | `data/update/UpdateSources.kt` (new) | `ReleaseSource` + `HttpReleaseSource`; `UpdateEndpoints` with the GitHub API and three static `update.json` mirrors (Pages / raw / jsDelivr); `classifyUpdateFailure()`; `ReleaseAssetSelector` |
| DTO | `data/remote/ReleaseApi.kt` | `draft`, `prerelease`, `published_at`, asset `size`/`content_type`/`digest`; GitHub `Accept` + `X-GitHub-Api-Version` headers; absolute-URL `@GET` |
| Repository | `data/update/UpdateRepositoryImpl.kt` | ordered failover, ≤2 attempts/source with 700 ms linear backoff, 45 s total budget, 1.5 s connectivity probe, draft/prerelease/unparsable-tag rejection, numeric semver compare, scored asset selection restricted to the official download path, `minimum_supported_version` force flag, honest cache writes, first-source diagnosis wins |
| Version compare | `domain/model/AppUpdate.kt` | `VersionComparator.canonical()` + `isNewer()` (strictly newer; unparsable is never newer) |
| HTTP clients | `di/NetworkModule.kt`, `data/update/UpdateQualifiers.kt` (new) | `@UpdateApi` (15/20/20 s, `callTimeout` 30 s) and `@UpdateDownloads` (**no `callTimeout`**, 20/30/30 s sockets) + a descriptive `User-Agent` (required by the GitHub API) |
| DI | `di/UpdateModule.kt` | provides the qualified `Retrofit`/`ReleaseApi`, the ordered source list, the check cache and the repository (with the real `Connectivity`) |
| Download | `data/update/ApkDownloader.kt` | status + `Content-Type` check (an HTML portal is rejected), streaming to `<name>.part`, completeness + 500 KB floor, SHA-256 (sidecar, else asset `digest`), `PackageManager` inspection, atomic rename, partial deleted on every failure/cancellation, bounded retry, `UpdateDownloadException` always carries a classified error |
| Verification | `data/update/ApkVerification.kt` (new) | `ApkVerifier.check()` → `ApkCheck.Ok/Rejected(kind, detail)`; `ApkPackageInspector` (readable archive, same package, `versionCode ≥` installed) |
| Install | `data/update/ApkInstaller.kt` | `InstallOutcome.Started / NeedsPermission(settingsOpened) / Failed(error)`; `content://` via `FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION`; `ActivityNotFoundException` mapped to `INSTALL_FAILED` |
| UI state machine | `ui/update/UpdateViewModel.kt`, `ui/update/UpdateDialog.kt` | `Hidden / Checking / Available / Downloading(percent) / Ready / NeedsPermission / Error(error, update?)`; `checkNow()`; retry targets the right phase; automatic checks stay silent |
| Settings | `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt` | the row shows the exact reason (`error.userMessage`) instead of a blanket failure |
| Release pipeline | `.github/workflows/android-release.yml`, `.github/scripts/verify-apk.sh` | publishes the API-shaped `update.json` to `gh-pages` (validated **before** upload), reads the mirrors back and asserts every field, reports the signer certificate fingerprint and compares it with the previous release, and lets `workflow_dispatch` create a tag that does not exist yet |
| Static gate | `scripts/verify-update-channel.sh` (new) | 77 assertions that the whole channel is present — runnable with no JDK/SDK |
| Version | `android/app/build.gradle.kts` | `versionCode = 4`, `versionName = "1.0.3"` |
| R8 (inert today) | `android/app/proguard-rules.pro` | keeps for the release DTOs and `UpdateErrorKind`, OkHttp/Okio `-dontwarn`s |
| Docs | `docs/RELEASE.md`, `README.md` | sources/failover, taxonomy table, download & verification chain, cache semantics |

## 4. Behavioural guarantees (and the tests that pin them)

108 tests cover the update path (159 in the whole suite).

| Scenario | Expected | Test |
|---|---|---|
| installed 1.0.1, latest v1.0.1 | `NoUpdate` («آخرین نسخه را دارید») | `UpdateRepositoryImplTest` ×3, `ShippedVersionUpdateTest` |
| installed 1.0.1, latest v1.0.2/v1.0.3 | `Available` + official URL/size/checksum | both |
| installed 1.0.2, latest v1.0.1 | `NoUpdate` (no downgrade) | both |
| 1.0.9 → v1.0.10 | `Available("1.0.10")` (numeric, not lexical) | both + `VersionComparatorTest` |
| timeout / DNS / TLS / 403 / 429 / 404 / 5xx / bad JSON | distinct `UpdateErrorKind`, never `NoUpdate` | `UpdateRepositoryImplTest`, `UpdateErrorMappingTest` |
| internet up, update server blocked | `CONNECTION_FAILED` = «ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید.» | both |
| device offline | `NO_INTERNET`, **zero** network calls | `UpdateRepositoryImplTest` |
| API filtered | failover to a mirror still yields `Available` | `UpdateRepositoryImplTest` |
| draft / prerelease / `tag: latest` | `RELEASE_NOT_FOUND`, never offered | `UpdateRepositoryImplTest` |
| release without an APK asset | `APK_NOT_FOUND` (not “up to date”) | `UpdateRepositoryImplTest` |
| mixed assets (`.sha256`, source zip, `mapping.txt`, debug/unsigned) | only the official APK; foreign/insecure URLs rejected | `ReleaseAssetSelectorTest` |
| corrupt/truncated package | blocked before the installer (`INCOMPLETE_DOWNLOAD`, `CHECKSUM_MISMATCH`, `INVALID_APK`) | `ApkVerifierTest` |
| automatic check inside 12 h | `NotDueYet`, no network call | both |
| manual check | always hits the network, re-notifies the dialog | both |
| failed check | cache untouched → next automatic check retries | `UpdateRepositoryImplTest` |
| dialog copy | Persian only, no jargon, no exception text, RTL unchanged | `UpdateDialogTest` |

## 5. Verification performed in the session (no device, no JVM)

* **Real payload check** — `GET /repos/javadisaloo1111/Currency-App/releases/latest`
  was fetched live and confirmed the DTO mapping: `tag_name: v1.0.2`,
  `draft: false`, `prerelease: false`, `published_at`, two assets
  (`app-release-v1.0.2.apk`, 8,679,105 B,
  `application/vnd.android.package-archive`, `digest: sha256:9759bc35…`;
  `app-release-v1.0.2.apk.sha256`, 89 B). Unknown keys (`state`,
  `download_count`, `immutable`) are ignored by the `Json` config.
* **Logic simulation** — the Kotlin algorithm (comparator → tag sanity → asset
  scoring → checksum sidecar → digest → force flag) was ported line-by-line to
  Python and run against that real payload: installed `1.0.1` →
  `Available(1.0.2, official URL, 8679105 B, sidecar URL, sha256 9759bc35…,
  forced=false)`; installed `1.0.2` / `1.0.3` / `1.0.10` → `NoUpdate`.
  The sidecar text (`<hex>  <name>\n`) parses with the downloader's regex and its
  hex equals the asset digest.
* **Mirror simulation** — the new workflow step was extracted from the YAML and
  executed with a stubbed `gh`: it emits valid JSON in the release shape
  (including the asset `digest`), calls the contents API with the existing blob
  SHA, and appends a note to the release body. Feeding that generated
  `update.json` through the same simulation produced a result **identical** to the
  API payload — i.e. the mirrors are true drop-in sources.
* **Static checks** — every Kotlin file was scanned for unbalanced
  braces/parens/brackets, duplicate top-level declarations per package, and
  unresolved `ir.talayar.app.*` imports (only generated `BuildConfig`/`R` and
  extension receivers, which the scanner cannot see). The workflow YAML parses.
  The secret scan over the full diff is clean.
* **Channel invariants** — `scripts/verify-update-channel.sh` (77 grep-level
  assertions over endpoints, failover, retry, timeouts, taxonomy, asset
  selection, the verification chain, FileProvider, installer, version numbers and
  signing behaviour): `0 failures`.
* **Mirror publish + read-back simulation** — both new workflow steps were
  extracted from the YAML and executed with stubbed `gh`/`curl`: the generated
  `update.json` validated, was "published", and all three mirrors then answered
  `OK — tag_name=v1.0.3, draft=false, prerelease=false, exactly one apk asset,
  apk url, apk size > 500KB, sha256 digest matches the published apk`, with
  `mirror.version_name=1.0.3` / `version_code=4`.
* **Mirror corruption guard** — with an implausible 100 KB "APK" the validation
  reported `BAD:apk size 100004`, warned, and **left the existing mirror
  byte-identical** (verified with `cmp`).
* **Certificate comparison simulation** — the new release step was run against a
  stubbed SDK/`gh` in three scenarios: different certificates (warning + Persian
  release-note line naming both fingerprints and the one-time uninstall),
  identical certificates (notice + "in-place update works"), and no previous
  release (notice, exit 0).
* **`verify-apk.sh` dry run** — executed against a synthetic APK with a stubbed
  `apksigner`: the new certificate section printed
  `signer certificate SHA-256 = …` into the log, the verification table and the
  Markdown report that is embedded in the release notes, and the pre-existing ZIP
  magic check correctly failed the synthetic file.

**Not done:** compilation, JVM unit tests, lint, an emulator/device run, and any
real download/install. Those need CI or a workstation.

## 6. Handoff — how to publish v1.0.3

Everything below is committed and ready. This session could not publish: its
GitHub access was revoked after the earlier pull request was merged, and it has no
JDK/Android SDK, so **CI must do the real build**.

### 6.1 Static verification (runs anywhere, no JDK/SDK needed)

```bash
./scripts/verify-update-channel.sh
```

77 assertions that the whole channel is present in the tree — endpoints, mirrors,
failover, bounded retry, timeouts, error taxonomy, asset selection, the
verification chain, FileProvider, the installer, the version numbers and the
signing behaviour. Current result:
`RESULT: all update-channel invariants present (0 failures)`.

### 6.2 Build + publish (from a session or machine with push access)

```bash
git checkout arena/01a094fb-currency-app
cd android && ./gradlew clean testDebugUnitTest assembleRelease   # fix any compile error
cd ..
git push origin arena/01a094fb-currency-app
gh pr create --base main --head arena/01a094fb-currency-app \
  --title "fix(update): production-grade in-app update channel (v1.0.3)" \
  --body "Audit, root cause, verification evidence and this handoff: docs/UPDATE-AUDIT.md"
gh pr checks --watch                       # Android CI must be green
gh pr merge --squash
git checkout main && git pull
git tag v1.0.3 && git push origin v1.0.3   # → the Android Release workflow
```

No local toolchain? After the merge, open **Actions → Android Release → Run
workflow** on `main` with `tag = v1.0.3`. The workflow now checks out the
dispatched ref and creates + pushes the tag itself; that ordering was broken
before (it tried to check out a tag that did not exist yet and aborted).

The release workflow then, in order: runs the unit tests, builds the signed APK,
verifies it (`applicationId` / `versionName` / `versionCode` read back **from the
package**, the 500 KB floor, the shipped UI copy, SHA-256 and the **signer
certificate fingerprint**), publishes `Gold Market Android v1.0.3` with
`app-release-v1.0.3.apk` + its `.sha256`, downloads the asset back and re-hashes
it, **compares the signing certificate with the previous published release**,
publishes `update.json` to `gh-pages` (validated first — a broken payload is never
written over a good mirror) and **reads the mirrors back** to assert `tag_name`,
`draft=false`, `prerelease=false`, the APK URL, its size and its SHA-256. Exactly
one Release exists per tag: do not create a second one.

### 6.3 Verify after the release (any machine with curl)

```bash
R=https://github.com/javadisaloo1111/Currency-App/releases/download/v1.0.3/app-release-v1.0.3.apk
curl -fsSL -o app-release-v1.0.3.apk "$R" && sha256sum app-release-v1.0.3.apk

# package / version / certificate (needs Android SDK build-tools)
"$ANDROID_HOME"/build-tools/*/aapt2 dump badging app-release-v1.0.3.apk | head -3
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs app-release-v1.0.3.apk

# the metadata the app itself will read
curl -fsSL https://api.github.com/repos/javadisaloo1111/Currency-App/releases/latest | head -30
for u in https://javadisaloo1111.github.io/Currency-App/update.json \
         https://raw.githubusercontent.com/javadisaloo1111/Currency-App/gh-pages/update.json \
         https://cdn.jsdelivr.net/gh/javadisaloo1111/Currency-App@gh-pages/update.json; do
  echo "--- $u"; curl -fsSL "$u"
done
```

Expected: `package: name='ir.talayar.app'`, `versionName='1.0.3'`,
`versionCode='4'`; `tag_name v1.0.3`, `draft false`, `prerelease false`, the
official download URL, a size above 500 KB, the asset `digest`, and
`mirror.version_name = 1.0.3` / `version_code = 4`. Pages, raw and jsDelivr are
CDN-cached — allow a few minutes after the release.

### 6.4 On the device

1. Install `app-release-v1.0.3.apk`.
2. If 1.0.1 or 1.0.2 is already installed **and** the certificates differ (the
   release notes now say so explicitly), Android refuses the in-place upgrade
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — uninstall once, then install.
3. In the app: تنظیمات ← «بررسی بروزرسانی» must say «آخرین نسخه را دارید» while
   v1.0.3 is the newest. After publishing v1.0.4 the same button must offer it —
   that is the end-to-end test of the new channel. To see the failover and the
   precise Persian error copy, test on a network where `api.github.com` is
   blocked: the mirrors must answer instead, and a genuinely offline device must
   say «اتصال اینترنت برقرار نیست…», never «ارتباط با سرور بروزرسانی برقرار نشد».

### 6.5 Signing (owner action — does not block this release)

Until `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` exist as repository secrets (recipe in
`docs/RELEASE.md`), every CI build invents a fresh debug certificate, so **v1.0.3
will not upgrade in place over v1.0.1/v1.0.2**. The APK is still perfectly
installable for testing after a one-time uninstall, and both the release notes and
the job annotations state the situation automatically. Setting the secrets once
fixes it permanently: from then on the in-app updater installs over the previous
version with no user action beyond confirming the system installer.

### 6.6 Adding a gateway or another mirror later

One URL in `UpdateEndpoints.MIRRORS` (`data/update/UpdateSources.kt`) serving the
same JSON shape. Nothing else — checker, selection, downloader, installer, UI —
changes.
