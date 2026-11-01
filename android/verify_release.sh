#!/usr/bin/env bash
# Sett release verification ritual (Phase 1, debt D9): everything that
# used to be checked by hand, in one command. Verifies the BUILT FILE,
# never the build log.
#
# Usage: android/verify_release.sh <apk> [expected-versionName]
set -u
APK="${1:?usage: verify_release.sh <apk> [versionName]}"
TOOLCHAIN="$HOME/workspace/toolchain"
BT="$TOOLCHAIN/android-sdk/build-tools/37.0.0"
export JAVA_HOME="$TOOLCHAIN/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
fail=0
check() { if [ "$2" -eq 0 ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi }

BADGING="$("$BT/aapt2" dump badging "$APK" 2>/dev/null)"
echo "$BADGING" | grep -q "name='app.privatex.browser'"; check "package app.privatex.browser" $?
echo "$BADGING" | grep -q "application-label:'Sett'"; check "label Sett" $?
if [ -n "${2:-}" ]; then
  echo "$BADGING" | grep -q "versionName='$2'"; check "versionName $2" $?
fi
echo "$BADGING" | grep -q "compileSdkVersion='36'"; check "compileSdk 36" $?
echo "$BADGING" | grep -q "targetSdkVersion:'36'"; check "targetSdk 36" $?

"$BT/apksigner" verify "$APK" >/dev/null 2>&1; check "apksigner verify" $?
V="$("$BT/apksigner" verify --verbose "$APK" 2>/dev/null)"
echo "$V" | grep -q "v2 scheme (APK Signature Scheme v2): true"; check "signature v2" $?
echo "$V" | grep -q "v3 scheme (APK Signature Scheme v3): true"; check "signature v3" $?
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "0a60cd9f3270e0929c1c0866aa8544dcd10b2a561ccca8bed9d4cf39c44424f2"
check "project signing cert" $?

"$BT/zipalign" -c -P 16 4 "$APK" >/dev/null 2>&1; check "zipalign 16K" $?
unzip -t "$APK" >/dev/null 2>&1; check "zip integrity" $?

TMP="$(mktemp -d)"
unzip -o -q "$APK" "classes*.dex" -d "$TMP"
for s in "sett://home" "privatex://home" "browser/errorpages/ErrorPages" "UndoMiddleware" "feature/prompts/PromptFeature" "storage/sync/PlacesHistoryStorage" "feature/readerview/ReaderViewFeature" "feature/contextmenu/ContextMenuFeature" "browser/LibraryActivity" "browser/ShieldActivity" "browser/VaultActivity" "vault/VaultCore" "browser/ExtensionsActivity" "browser/NetworkActivity" "browser/SpacesActivity" "browser/UtilitiesActivity" "tools/MetaClean" "browser/DeveloperActivity" "tools/DevTools" "browser/SearchActivity" "tools/SearchRouting" "browser/ResearchActivity" "tools/ScholarTools" "research/ResearchStore" "browser/StudyActivity" "study/StudyStore" "study/Sm2" "browser/AiActivity" "tools/AiTools" "browser/IndexActivity" "tools/LocalIndex" "ui/SettTheme" "ui/SettUi" \
"home/HomeStore" "home/HomePageBuilder" "sett://resolve" "sett://action/"; do
  if cat "$TMP"/classes*.dex | strings | grep -q "$s"; then check "DEX contains $s" 0; else check "DEX contains $s" 1; fi
done
rm -rf "$TMP"

echo "SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)  ($APK, $(stat -c%s "$APK") bytes)"
[ "$fail" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
