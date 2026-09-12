#!/usr/bin/env bash
# Static verification of the in-app update channel — runs without a JVM/SDK, so it
# can be checked on any machine (and before CI) to prove the v1.0.3 overhaul is
# actually present in the tree. Prints one line per requirement: PASS/FAIL + evidence.
set -uo pipefail
cd "$(dirname "$0")/.."
M=android/app/src/main/java/ir/talayar/app
T=android/app/src/test/java/ir/talayar/app
W=.github/workflows/android-release.yml
fails=0

check() { # check <label> <file> <regex> — the pattern MUST be present
  local label="$1" file="$2" rx="$3" hit
  hit=$(grep -nE "$rx" "$file" 2>/dev/null | head -1)
  if [ -n "$hit" ]; then
    printf 'PASS  %-42s %s:%s\n' "$label" "${file##*/}" "${hit%%:*}"
  else
    printf 'FAIL  %-42s (missing: %s in %s)\n' "$label" "$rx" "$file"; fails=$((fails+1))
  fi
}

check_absent() { # check_absent <label> <path> <regex> — the pattern MUST NOT exist
  local label="$1" path="$2" rx="$3" hit
  hit=$(grep -rnE "$rx" --include='*.kt' --include='*.xml' "$path" 2>/dev/null | head -1)
  if [ -z "$hit" ]; then
    printf 'PASS  %-42s (absent, as required)\n' "$label"
  else
    printf 'FAIL  %-42s %s\n' "$label" "$hit"; fails=$((fails+1))
  fi
}

echo "== version =="
check "versionName 1.0.3"          android/app/build.gradle.kts 'val appVersionName = "1\.0\.3"'
check "versionCode 4"              android/app/build.gradle.kts 'val appVersionCode = 4'
check "applicationId ir.talayar.app" android/app/build.gradle.kts 'applicationId = "ir\.talayar\.app"'

echo "== 1. GitHub API endpoint + headers =="
check "releases/latest endpoint"   $M/data/update/UpdateSources.kt 'api\.github\.com/repos/\$OWNER/\$REPO/releases/latest'
check "Accept header"              $M/data/remote/ReleaseApi.kt 'application/vnd\.github\+json'
check "X-GitHub-Api-Version"       $M/data/remote/ReleaseApi.kt 'X-GitHub-Api-Version: 2022-11-28'
check "tag_name parsed"            $M/data/remote/ReleaseApi.kt '@SerialName\("tag_name"\)'
check "draft/prerelease fields"    $M/data/remote/ReleaseApi.kt '@SerialName\("prerelease"\)'
check "draft/prerelease rejected"  $M/data/update/UpdateRepositoryImpl.kt 'if \(dto\.draft \|\| dto\.prerelease\)'

echo "== 2. update.json mirrors =="
check "mirror: GitHub Pages"       $M/data/update/UpdateSources.kt 'github\.io/\$REPO/update\.json'
check "mirror: raw.githubusercontent" $M/data/update/UpdateSources.kt 'raw\.githubusercontent\.com'
check "mirror: jsDelivr CDN"       $M/data/update/UpdateSources.kt 'cdn\.jsdelivr\.net'
check "workflow publishes mirror"  $W 'Publish the static update mirror'
check "mirror written to gh-pages" $W 'branch="gh-pages"'
check "mirror carries digest"      $W 'MIRROR_APK_DIGEST'

echo "== 3. Failover =="
check "ReleaseSource interface"    $M/data/update/UpdateSources.kt 'interface ReleaseSource'
check "ordered default sources"    $M/data/update/UpdateSources.kt 'fun defaultReleaseSources'
check "failover loop over sources" $M/data/update/UpdateRepositoryImpl.kt 'for \(source in sources\)'
check "failover test"              $T/data/update/UpdateRepositoryImplTest.kt 'falls over to the static mirror'

echo "== 4. Retry (bounded, with backoff) =="
check "max attempts per source"    $M/data/update/UpdateRepositoryImpl.kt 'MAX_ATTEMPTS_PER_SOURCE = 2'
check "backoff base"               $M/data/update/UpdateRepositoryImpl.kt 'BACKOFF_BASE_MS = 700L'
check "retry only if retryable"    $M/data/update/UpdateRepositoryImpl.kt 'if \(!lastError\.retryable\) break'
check "download retries bounded"   $M/data/update/ApkDownloader.kt 'MAX_ATTEMPTS = 2'
check "no-retry-storm test"        $T/data/update/UpdateRepositoryImplTest.kt 'is not retried'

echo "== 5. Timeouts =="
check "metadata callTimeout 30s"   $M/di/NetworkModule.kt 'callTimeout\(30, TimeUnit\.SECONDS\)'
check "download callTimeout 0"     $M/di/NetworkModule.kt 'callTimeout\(0, TimeUnit\.MILLISECONDS\)'
check "download socket timeouts"   $M/di/NetworkModule.kt 'readTimeout\(30, TimeUnit\.SECONDS\)'
check "total check budget 45s"     $M/data/update/UpdateRepositoryImpl.kt 'DEFAULT_TOTAL_BUDGET_MS: Long = 45_000L'
check "connectivity probe 1.5s"    $M/data/update/UpdateRepositoryImpl.kt 'CONNECTIVITY_PROBE_MS: Long = 1_500L'
check "User-Agent set"             $M/di/NetworkModule.kt 'header\("User-Agent"'
check_absent "no TLS bypass anywhere" android/app/src/main 'sslSocketFactory|hostnameVerifier|X509TrustManager|trustAllCerts'

echo "== 6. Error handling =="
check "18 error kinds"             $M/domain/model/UpdateError.kt 'enum class UpdateErrorKind'
check "classifier"                 $M/data/update/UpdateSources.kt 'fun classifyUpdateFailure'
check "online vs offline split"    $M/data/update/UpdateSources.kt 'UpdateErrorKind\.NO_INTERNET'
check "server-unreachable copy"    $M/domain/model/UpdateError.kt 'ارتباط با سرور بروزرسانی برقرار نشد'
check "NotDueYet != NoUpdate"      $M/domain/repository/UpdateRepository.kt 'data object NotDueYet'
check "Settings shows the reason"  $M/ui/settings/SettingsScreen.kt 'UpdateCheckState\.Failed -> updateCheck\.message'

echo "== 7. APK asset selection =="
check "scored selector"            $M/data/update/UpdateSources.kt 'object ReleaseAssetSelector'
check "official prefix enforced"   $M/data/update/UpdateSources.kt 'OFFICIAL_ASSET_PREFIX\)'
check "browser_download_url used"  $M/data/remote/ReleaseApi.kt '@SerialName\("browser_download_url"\)'
check "token-based rejection"      $M/data/update/UpdateSources.kt 'REJECTED_TOKENS'
check "selector tests"             $T/data/update/ReleaseAssetSelectorTest.kt 'never picks source archives'

echo "== 8. APK verification =="
check "check() returns kinds"      $M/data/update/ApkVerification.kt 'fun check\(file: File'
check "ZIP magic"                  $M/data/update/ApkVerification.kt "missing ZIP/APK magic"
check "SHA-256 verification"       $M/data/update/ApkVerification.kt 'MessageDigest\.getInstance\("SHA-256"\)'
check "PackageManager inspection"  $M/data/update/ApkVerification.kt 'getPackageArchiveInfo'
check "same-package guard"         $M/data/update/ApkVerification.kt 'packageName != context\.packageName'
check "no-downgrade guard"         $M/data/update/ApkVerification.kt 'downloadedCode < installedCode'
check ".part then atomic rename"   $M/data/update/ApkDownloader.kt 'part\.renameTo\(target\)'
check "partial deleted on failure" $M/data/update/ApkDownloader.kt 'part\.delete\(\)'
check "digest fallback"            $M/data/update/ApkDownloader.kt 'sidecarSha256 \?: update\.sha256'

echo "== 9. Version comparator =="
check "numeric parse"              $M/domain/model/AppUpdate.kt 'IntArray\?'
check "canonical()"                $M/domain/model/AppUpdate.kt 'fun canonical'
check "isNewer() strictly greater" $M/domain/model/AppUpdate.kt 'compare\(candidate, installed\) > 0'
check "1.0.10 > 1.0.9 test"        $T/domain/model/VersionComparatorTest.kt '1_0_10 is newer than 1_0_9|isNewer\("1\.0\.10", "1\.0\.9"\)'

echo "== 10. FileProvider =="
check "content:// via FileProvider" $M/data/update/ApkInstaller.kt 'FileProvider\.getUriForFile'
check "read grant flag"            $M/data/update/ApkInstaller.kt 'FLAG_GRANT_READ_URI_PERMISSION'
check "authority suffix"           $M/data/update/ApkInstaller.kt 'AUTHORITY_SUFFIX = "\.fileprovider"'
check "manifest provider"          android/app/src/main/AndroidManifest.xml 'authorities="\$\{applicationId\}\.fileprovider"'
check "cache-path updates/"        android/app/src/main/res/xml/file_paths.xml 'cache-path name="updates"'
check "REQUEST_INSTALL_PACKAGES"   android/app/src/main/AndroidManifest.xml 'REQUEST_INSTALL_PACKAGES'
check "cleartext blocked"          android/app/src/main/res/xml/network_security_config.xml 'cleartextTrafficPermitted="false"'

echo "== 11. Installer / state machine =="
check "InstallOutcome sealed"      $M/data/update/ApkInstaller.kt 'sealed interface InstallOutcome'
check "needs-permission path"      $M/data/update/ApkInstaller.kt 'canRequestPackageInstalls'
check "ACTION_VIEW + APK mime"     $M/data/update/ApkInstaller.kt 'APK_MIME = "application/vnd\.android\.package-archive"'
check_absent "no silent install"      android/app/src/main 'PackageInstaller|INSTALL_SESSION|installExistingPackage'
check "state machine"              $M/ui/update/UpdateViewModel.kt 'sealed interface State'
check "Checking state"             $M/ui/update/UpdateViewModel.kt 'data object Checking'
check "manual checkNow()"          $M/ui/update/UpdateViewModel.kt 'override fun checkNow'

echo "== signing (report only — a missing secret must not block the build) =="
check "keystore secret consumed"   $W 'ANDROID_KEYSTORE_BASE64'
check "warns when secret missing"  $W 'signed with the debug key'
check "release falls back to debug signing" android/app/build.gradle.kts 'signingConfigs.getByName\("debug"\)'
check "release keeps real applicationId" android/app/build.gradle.kts 'applicationId = "ir.talayar.app"'
check "release versionName from one source" android/app/build.gradle.kts 'versionName = appVersionName'
check "release versionCode from one source" android/app/build.gradle.kts 'versionCode = appVersionCode'
check "cert fingerprint reported"  .github/scripts/verify-apk.sh 'apksigner|SHA-256|fingerprint'
check "APK metadata verified in CI" $W 'verify-apk.sh'

echo
if [ "$fails" -eq 0 ]; then echo "RESULT: all update-channel invariants present ($fails failures)"; else echo "RESULT: $fails FAILURE(S)"; fi
exit "$fails"
