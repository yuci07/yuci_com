#!/usr/bin/env bash
# ============================================================
#  yuci_com build script for Linux / macOS
#  Usage: ./build.sh        compile and build the fat jar
#         ./build.sh dist   also build a self-contained app-image
#  Env  : YUCI_JAVA=/path/to/jdk   override the JDK location
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JSC="lib/jSerialComm-2.11.4.jar"
SRC="src/YuciCom.java"
BUILD="build"

# ---- locate JDK ----
if [[ -n "${YUCI_JAVA:-}" ]]; then
  JDK="$YUCI_JAVA"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JDK="$JAVA_HOME"
elif command -v javac >/dev/null 2>&1; then
  JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else
  echo "[ERROR] JDK 21+ not found. Install one or set YUCI_JAVA." >&2
  exit 1
fi

[[ -f "$JSC" ]] || { echo "[ERROR] missing $JSC" >&2; exit 1; }

echo "[1/4] clean"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/jar"

echo "[2/4] compile"
"$JDK/bin/javac" -encoding UTF-8 -nowarn -d "$BUILD/classes" -cp "$JSC" "$SRC"

echo "[3/4] merge jSerialComm into fat jar"
(
  cd "$BUILD/classes"
  "$JDK/bin/jar" xf "$ROOT/$JSC"
  rm -f META-INF/MANIFEST.MF
  cp "$ROOT/icon.png" icon.png
)
{
  echo "Main-Class: YuciCom"
  echo "Multi-Release: true"
  echo "Implementation-Title: yuci_com"
  echo "Implementation-Version: 2.1"
} > "$BUILD/manifest.txt"

(
  cd "$BUILD/classes"
  "$JDK/bin/jar" cfm "$BUILD/jar/yuci_com.jar" "$BUILD/manifest.txt" .
)

echo "[4/4] verify"
"$JDK/bin/java" -Dstdout.encoding=UTF-8 -cp "$BUILD/jar/yuci_com.jar" YuciCom --selftest

echo
echo "DONE: $BUILD/jar/yuci_com.jar"

if [[ "${1:-}" == "dist" ]]; then
  echo "building app-image ..."
  rm -rf dist/yuci_com
  mkdir -p dist
  "$JDK/bin/jpackage" \
    --type app-image \
    --name yuci_com \
    --input "$BUILD/jar" \
    --main-jar yuci_com.jar \
    --main-class YuciCom \
    --dest dist \
    --app-version 2.1 \
    --vendor yuci \
    --java-options "-Xmx512m"
  echo "DONE: dist/yuci_com/"
fi
