#!/bin/bash
# Manual APK build for Call Log Vault: aapt2 + kotlinc + d8 + apksigner,
# no Gradle. Produces a signed APK in the project root.
#
# Signing is supplied by the builder through environment variables;
# nothing about any particular machine or keystore lives in this script:
#
#   CLV_KEYSTORE        Path to your keystore file. Required.
#   CLV_STORE_PW_FILE   Path to a file containing the keystore password,
#                       or set CLV_STORE_PASSWORD to the password itself.
#   CLV_KEY_ALIAS       Key alias inside the keystore (default: release).
#
# Toolchain locations can also be overridden by environment:
#   JAVA_HOME     JDK 17. If unset, java/javac are taken from PATH.
#   ANDROID_HOME  Android SDK (default: ~/Android/Sdk). Needs platform
#                 android-34 and build-tools 34.0.0.
#   KOTLINC       Kotlin compiler launcher (default: kotlinc from PATH).
#   KOTLIN_STDLIB Path to kotlin-stdlib.jar. If unset, it is derived
#                 from the kotlinc location (../lib/kotlin-stdlib.jar).
#
# Official releases are signed with the maintainer's own keystore, and
# Android only accepts an update to an installed app when the update is
# signed with the same key. If you build with your own key, install the
# result as a fresh app (uninstall any official build first).
set -euo pipefail

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KOTLINC="${KOTLINC:-kotlinc}"
if [ -z "${KOTLIN_STDLIB:-}" ]; then
  KOTLINC_BIN="$(command -v "$KOTLINC" || echo "$KOTLINC")"
  KOTLINC_HOME="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
  KOTLIN_STDLIB="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
fi

# Signing inputs (see header). Fail early with a clear message.
if [ -z "${CLV_KEYSTORE:-}" ]; then
  echo "error: CLV_KEYSTORE is not set. Point it at your keystore file." >&2
  echo "Example: CLV_KEYSTORE=/path/to/my.keystore CLV_STORE_PW_FILE=/path/to/pw.txt ./build-manual.sh" >&2
  exit 1
fi
if [ -n "${CLV_STORE_PW_FILE:-}" ]; then
  STORE_PW="$(cat "$CLV_STORE_PW_FILE")"
elif [ -n "${CLV_STORE_PASSWORD:-}" ]; then
  STORE_PW="$CLV_STORE_PASSWORD"
else
  echo "error: set CLV_STORE_PW_FILE (file containing the password) or CLV_STORE_PASSWORD." >&2
  exit 1
fi
KEY_ALIAS="${CLV_KEY_ALIAS:-release}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
BUILD="$APP/build/manual"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
APK_NAME="${APK_NAME:-call-log-vault-v${VERSION_NAME}.apk}"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== 1. aapt2 compile (resources)"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$BUILD/compiled_res.zip"

echo "== 2. aapt2 link (base APK + R.java)"
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$BUILD/compiled_res.zip"

echo "== 2b. normalize R.java package (manifest package is the appId pro.calllogvault.app)"
R_JAVA="$(find "$BUILD/gen" -name R.java | head -1)"
sed -i 's/^package .*$/package com.calllogvault;/' "$R_JAVA"
grep -m1 '^package' "$R_JAVA"

echo "== 3. kotlinc (framework APIs only, no AndroidX)"
"$KOTLINC" "$APP"/src/main/java/com/calllogvault/*.kt "$R_JAVA" \
  -jvm-target 1.8 -no-reflect -no-stdlib \
  -cp "$ANDROID_JAR:$KOTLIN_STDLIB" \
  -d "$BUILD/classes"

echo "== 4. d8 (dex)"
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class") \
  "$KOTLIN_STDLIB"

echo "== 5. add dex to APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -X ../unsigned.apk classes*.dex )

echo "== 6. zipalign"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 7. apksigner (keystore supplied via CLV_KEYSTORE)"
"$APKSIGNER" sign --ks "$CLV_KEYSTORE" \
  --ks-pass "pass:$STORE_PW" --key-pass "pass:$STORE_PW" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$BUILD/call-log-vault.apk" "$BUILD/aligned.apk"

echo "== 8. verify"
"$APKSIGNER" verify --print-certs "$BUILD/call-log-vault.apk" | head -8
"$AAPT2" dump badging "$BUILD/call-log-vault.apk" > "$BUILD/badging.txt" 2>&1 || true
head -6 "$BUILD/badging.txt"

cp "$BUILD/call-log-vault.apk" "$ROOT/$APK_NAME"
ls -la "$ROOT/$APK_NAME"
sha256sum "$ROOT/$APK_NAME"
echo "BUILD_OK"
