#!/usr/bin/env bash
#
# Verifies a built release APK *before* it is published (used by Android CI and
# by the Android Release workflow).
#
# Checks:
#   1. the file exists, is a real ZIP/APK ("PK\x03\x04" magic) and is not truncated
#   2. the packaged AndroidManifest reports the expected applicationId,
#      versionName and versionCode
#   3. an optional required string (e.g. a piece of UI copy) is really shipped
#      inside the APK (dex / resources)
#
# Version metadata is read from the APK with the first tool available
# (aapt2 -> aapt -> apkanalyzer -> AGP merged-manifest intermediates), so the
# check does not depend on one specific SDK component being installed.
#
# Usage:
#   verify-apk.sh --apk <file> [--gradle <app/build.gradle.kts>] [--tag <vX.Y.Z>]
#                 [--string <required string>] [--report <markdown file>]
#
#   --gradle  build file the expected versionName/versionCode are read from
#   --tag     when given, the expected versionName is the tag without the "v"
#             (proves the tagged release really contains that version)
#   --string  literal that must be present inside the APK
#   --report  optional path of a Markdown report (embedded in the release notes)
#
# Exit code: 0 when every executed check passed, 1 on any mismatch.

set -uo pipefail

APK="" GRADLE="" TAG="" REQUIRED="" REPORT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk)     APK="$2";      shift 2 ;;
    --gradle)  GRADLE="$2";   shift 2 ;;
    --tag)     TAG="$2";      shift 2 ;;
    --string)  REQUIRED="$2"; shift 2 ;;
    --report)  REPORT="$2";   shift 2 ;;
    *) echo "verify-apk.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

ERRORS=0
RESULT_LINES=()

note() { echo "$*"; }
ok()   { echo "  [ok]    $*"; RESULT_LINES+=("| ✅ | $* |"); }
bad()  { echo "  [FAIL]  $*"; echo "::error::verify-apk: $*"; RESULT_LINES+=("| ❌ | $* |"); ERRORS=$((ERRORS + 1)); }
warn() { echo "  [warn]  $*"; echo "::warning::verify-apk: $*"; RESULT_LINES+=("| ⚠️ | $* |"); }

if [ -z "$APK" ]; then
  echo "verify-apk.sh: --apk is required" >&2
  exit 2
fi
if [ ! -f "$APK" ]; then
  bad "APK not found: $APK"
  exit 1
fi
# Absolute path, so the checks below can cd freely.
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"

echo "== verify-apk =="
echo "apk: $APK"

# ---------------------------------------------------------------------------
# 1. expected version (from the tag and/or the gradle build file)
# ---------------------------------------------------------------------------
GRADLE_NAME="" GRADLE_CODE=""
if [ -n "$GRADLE" ] && [ -f "$GRADLE" ]; then
  # Preferred form: the single-source-of-truth vals at the top of the build file.
  GRADLE_NAME=$(sed -n 's/^val appVersionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)
  GRADLE_CODE=$(sed -n 's/^val appVersionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)
  # Tolerate the older inline form inside defaultConfig { ... }.
  [ -n "$GRADLE_NAME" ] || GRADLE_NAME=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)
  [ -n "$GRADLE_CODE" ] || GRADLE_CODE=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)
fi

EXPECTED_NAME=""
if [ -n "$TAG" ]; then EXPECTED_NAME="${TAG#v}"; fi
[ -n "$EXPECTED_NAME" ] || EXPECTED_NAME="$GRADLE_NAME"
EXPECTED_CODE="$GRADLE_CODE"

echo "expected versionName: ${EXPECTED_NAME:-<unknown>}"
echo "expected versionCode: ${EXPECTED_CODE:-<unknown>}"

# ---------------------------------------------------------------------------
# 2. package integrity
# ---------------------------------------------------------------------------
SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK" 2>/dev/null || echo 0)
MAGIC=$(head -c 2 "$APK" 2>/dev/null || true)
if [ "$MAGIC" = "PK" ]; then
  ok "APK is a ZIP archive (${SIZE} bytes)"
else
  bad "APK does not start with the ZIP magic 'PK' (got '$MAGIC')"
fi
# The in-app updater rejects anything below 500 KB (ApkDownloader.MIN_APK_BYTES).
if [ "$SIZE" -gt 500000 ]; then
  ok "APK size is above the in-app updater minimum (500 KB)"
else
  bad "APK is only ${SIZE} bytes — the in-app updater would reject it"
fi

# ---------------------------------------------------------------------------
# 3. version metadata from the APK itself
# ---------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2="" AAPT="" ANALYZER=""
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  AAPT2=$(find "$SDK/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -1)
  AAPT=$(find "$SDK/build-tools" -maxdepth 2 -name aapt -type f 2>/dev/null | sort -V | tail -1)
  ANALYZER=$(find "$SDK/cmdline-tools" "$SDK/tools" -maxdepth 3 -name apkanalyzer -type f 2>/dev/null | sort -V | tail -1)
fi

PKG="" VNAME="" VCODE="" TOOL=""
if [ -n "$AAPT2" ]; then
  BADGING=$("$AAPT2" dump badging "$APK" 2>/dev/null) || BADGING=""
  if [ -n "$BADGING" ]; then
    PKG=$(printf '%s\n' "$BADGING"   | sed -n "s/^package: name='\([^']*\)'.*/\1/p"        | head -1)
    VNAME=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p"          | head -1)
    VCODE=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"          | head -1)
    TOOL="aapt2 ($("$AAPT2" version 2>/dev/null | head -1))"
  fi
fi
if [ -z "$VNAME" ] && [ -n "$AAPT" ]; then
  BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null) || BADGING=""
  if [ -n "$BADGING" ]; then
    PKG=$(printf '%s\n' "$BADGING"   | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
    VNAME=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p"   | head -1)
    VCODE=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"   | head -1)
    TOOL="aapt"
  fi
fi
if [ -z "$VNAME" ] && [ -n "$ANALYZER" ]; then
  MANIFEST=$("$ANALYZER" manifest print "$APK" 2>/dev/null) || MANIFEST=""
  if [ -n "$MANIFEST" ]; then
    FLAT=$(printf '%s' "$MANIFEST" | tr -d '\n')
    PKG=$(printf '%s' "$FLAT"   | sed -n 's/.*package="\([^"]*\)".*/\1/p'                  | head -1)
    VNAME=$(printf '%s' "$FLAT" | sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p'       | head -1)
    VCODE=$(printf '%s' "$FLAT" | sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p'       | head -1)
    TOOL="apkanalyzer"
  fi
fi
if [ -z "$VNAME" ]; then
  # Last resort: the plain-text merged manifest AGP produced for this build.
  BUILD_DIR="$(cd "$(dirname "$APK")/../../.." 2>/dev/null && pwd)"
  MERGED=$(find "$BUILD_DIR/intermediates" -name AndroidManifest.xml -path '*manifest*' 2>/dev/null | head -1)
  if [ -n "$MERGED" ] && [ -f "$MERGED" ]; then
    FLAT=$(tr -d '\n' < "$MERGED")
    PKG=$(printf '%s' "$FLAT"   | sed -n 's/.*package="\([^"]*\)".*/\1/p'            | head -1)
    VNAME=$(printf '%s' "$FLAT" | sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' | head -1)
    VCODE=$(printf '%s' "$FLAT" | sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' | head -1)
    TOOL="merged manifest ($MERGED)"
  fi
fi

if [ -z "$VNAME" ] && [ -z "$VCODE" ]; then
  warn "no APK manifest reader available (aapt2/aapt/apkanalyzer/merged manifest) — version assertions skipped"
else
  note "read from: ${TOOL}"
  note "package='$PKG' versionName='$VNAME' versionCode='$VCODE'"

  if [ -n "$PKG" ]; then
    if [ "$PKG" = "ir.talayar.app" ]; then ok "applicationId = $PKG"; else bad "unexpected applicationId '$PKG'"; fi
  fi
  if [ -n "$EXPECTED_NAME" ]; then
    if [ "$VNAME" = "$EXPECTED_NAME" ]; then ok "APK versionName = $VNAME"; else bad "APK versionName '$VNAME' != expected '$EXPECTED_NAME'"; fi
  else
    warn "no expected versionName given (--tag/--gradle) — reported APK value: '$VNAME'"
  fi
  if [ -n "$EXPECTED_CODE" ]; then
    if [ "$VCODE" = "$EXPECTED_CODE" ]; then ok "APK versionCode = $VCODE"; else bad "APK versionCode '$VCODE' != expected '$EXPECTED_CODE'"; fi
  else
    warn "no expected versionCode found in the gradle file — reported APK value: '$VCODE'"
  fi
fi

# ---------------------------------------------------------------------------
# 4. required copy is really shipped inside the APK
# ---------------------------------------------------------------------------
if [ -n "$REQUIRED" ]; then
  TMP=$(mktemp -d)
  ( cd "$TMP" && unzip -oq "$APK" 'classes*.dex' 'resources.arsc' 'res/*' 2>/dev/null ) || true
  if LC_ALL=C grep -rqF -- "$REQUIRED" "$TMP" 2>/dev/null; then
    ok "required text is shipped inside the APK: «$REQUIRED»"
  else
    bad "required text NOT found inside the APK: «$REQUIRED»"
  fi
  rm -rf "$TMP"
fi

# ---------------------------------------------------------------------------
# 5. checksum (published next to the APK as an .sha256 asset)
# ---------------------------------------------------------------------------
SHA=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1)
[ -n "$SHA" ] || SHA=$(shasum -a 256 "$APK" 2>/dev/null | cut -d' ' -f1)
if [ -n "$SHA" ]; then ok "SHA-256 = $SHA"; else warn "could not compute SHA-256"; fi

# ---------------------------------------------------------------------------
# 6. signing certificate — public fingerprint only. Two APKs signed with
#    different certificates cannot be upgraded in place (Android refuses with
#    INSTALL_FAILED_UPDATE_INCOMPATIBLE), which is exactly what the in-app updater
#    hands to the system installer, so every build reports which key signed it.
#    Warn-only: an unreadable certificate must never fail a release.
# ---------------------------------------------------------------------------
CERT_SHA=""
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
if [ -d "$SDK/build-tools" ]; then
  # minSdk 26 -> AGP signs with APK Signature Scheme v2 only (no JAR/v1 signature),
  # so keytool cannot see it; apksigner must. Try every build-tools, newest first.
  for tool in $(find "$SDK/build-tools" -name apksigner -type f 2>/dev/null | sort -Vr); do
    CERT_SHA="$("$tool" verify --print-certs "$APK" 2>/dev/null \
      | sed -n 's/^[[:space:]]*Signer #1 certificate SHA-256 digest:[[:space:]]*//p' | head -1)"
    if [ -n "$CERT_SHA" ]; then break; fi
  done
fi
if [ -z "$CERT_SHA" ] && command -v keytool >/dev/null 2>&1; then
  CERT_SHA="$(keytool -printcert -jarfile "$APK" 2>/dev/null | sed -n 's/^SHA256: //p' | head -1 | tr -d ':')"
fi
if [ -n "$CERT_SHA" ]; then
  ok "signer certificate SHA-256 = $CERT_SHA"
else
  warn "could not read the signer certificate (apksigner/keytool unavailable or unsigned APK)"
fi

# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
if [ -n "$REPORT" ]; then
  {
    echo "| | مورد |"
    echo "|---|---|"
    for line in "${RESULT_LINES[@]}"; do echo "$line"; done
    echo ""
    echo "- فایل: \`$(basename "$APK")\` — ${SIZE} bytes"
    [ -n "$SHA" ] && echo "- SHA-256: \`${SHA}\`"
    [ -n "$VNAME" ] && echo "- \`versionName=${VNAME}\` / \`versionCode=${VCODE}\` / \`applicationId=${PKG}\`"
    [ -n "$CERT_SHA" ] && echo "- signer certificate SHA-256: \`${CERT_SHA}\`"
  } > "$REPORT"
  note "report written to $REPORT"
fi

echo "================"
if [ "$ERRORS" -gt 0 ]; then
  echo "verify-apk: $ERRORS check(s) FAILED"
  exit 1
fi
echo "verify-apk: all checks passed"
