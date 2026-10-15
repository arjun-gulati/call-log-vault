#!/bin/bash
# JVM tests for the pure logic in VaultLogic.kt. No Android code runs here.
#
# Toolchain locations can be overridden from the environment:
#   JAVA_HOME      a JDK 17+ install (its bin is put on PATH)
#   KOTLINC        path to the kotlinc launcher (default: kotlinc on PATH)
#   KOTLIN_STDLIB  path to kotlin-stdlib.jar (default: derived from kotlinc)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
KOTLINC="${KOTLINC:-kotlinc}"
if [ -n "${KOTLIN_STDLIB:-}" ]; then
  STDLIB="$KOTLIN_STDLIB"
else
  STDLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
fi
OUT="${TMPDIR:-/tmp}/calllogvault-tests"
rm -rf "$OUT"; mkdir -p "$OUT"
"$KOTLINC" "$ROOT/app/src/main/java/com/calllogvault/VaultLogic.kt" "$ROOT/tests/VaultLogicTest.kt" \
  -cp "$STDLIB" -d "$OUT/tests.jar"
java -cp "$STDLIB:$OUT/tests.jar" VaultLogicTestKt
