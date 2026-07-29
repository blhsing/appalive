#!/usr/bin/env bash
#
# Offline APK build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Deliberately does not use Gradle. This needs nothing but the Android SDK
# build-tools, a platform android.jar and a JDK - all of which are already on
# the machine - so it works with no network access and no AGP download.
# A Gradle project is included for Android Studio, but THIS is the supported
# and verified build path.
#
# Usage:  ./build.sh [--clean] [--target-sdk N]
#
set -euo pipefail

# --------------------------------------------------------------- knobs
PKG="com.tmc.appalive"
MIN_SDK=21
# See README: 28 is deliberate. It exempts us from the Android 14+ foreground
# service restrictions, Android 15's BOOT_COMPLETED FGS limits, the exact-alarm
# permission gate and POST_NOTIFICATIONS runtime gating - every one of which
# otherwise helps the task killer. This is the single knob to change.
TARGET_SDK=28
COMPILE_SDK=35
VERSION_CODE=1
VERSION_NAME="1.0.0"

KEY_ALIAS="appalive"
KEY_PASS="appalive"

# --------------------------------------------------------------- plumbing
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
KEYSTORE="$ROOT/keystore/appalive.jks"

CLEAN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --clean) CLEAN=1 ;;
        --target-sdk) TARGET_SDK="$2"; shift ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "build.sh: unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "" >&2; echo "BUILD FAILED: $*" >&2; exit 1; }
step() { echo ""; echo "==> $*"; }

# Path conversion for the Windows SDK/JDK executables. cygpath -m yields
# "D:/path" which every Java and Android tool accepts. No-ops off Windows.
wpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
upath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}

# --------------------------------------------------------------- toolchain
SDK_RAW="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK_RAW" ] || die "ANDROID_HOME / ANDROID_SDK_ROOT is not set."
SDK="$(upath "$SDK_RAW")"
[ -d "$SDK" ] || die "Android SDK not found at: $SDK"

[ -d "$SDK/build-tools" ] || die "No build-tools in $SDK. Install with sdkmanager."
BT="$(ls -1d "$SDK/build-tools"/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
[ -n "$BT" ] || die "No build-tools version found in $SDK/build-tools"

PLATFORM="$SDK/platforms/android-$COMPILE_SDK"
if [ ! -f "$PLATFORM/android.jar" ]; then
    PLATFORM="$(ls -1d "$SDK/platforms"/android-*/ 2>/dev/null | sort -V | tail -1)"
    PLATFORM="${PLATFORM%/}"
    [ -f "$PLATFORM/android.jar" ] \
        || die "No platform android.jar found under $SDK/platforms"
    echo "note: android-$COMPILE_SDK not installed, using $(basename "$PLATFORM")"
fi
ANDROID_JAR="$PLATFORM/android.jar"

AAPT2="$BT/aapt2"; [ -x "$AAPT2" ] || AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign.exe"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"

[ -x "$AAPT2" ] || die "aapt2 not found in $BT"
[ -x "$ZIPALIGN" ] || die "zipalign not found in $BT"
[ -f "$D8_JAR" ] || die "d8.jar not found in $BT/lib"
[ -f "$APKSIGNER_JAR" ] || die "apksigner.jar not found in $BT/lib"

# Prefer JAVA_HOME: it is JDK 17 here, and newer JDKs have been dropping
# -source 8 support, which we need to emit class files d8 is happy with.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$(upath "$JAVA_HOME")/bin/javac" ]; then
    JHOME="$(upath "$JAVA_HOME")"
    JAVAC="$JHOME/bin/javac"; JAVA="$JHOME/bin/java"
    JAR="$JHOME/bin/jar"; KEYTOOL="$JHOME/bin/keytool"
else
    command -v javac >/dev/null 2>&1 || die "No javac on PATH and JAVA_HOME unusable."
    JAVAC=javac; JAVA=java; JAR=jar; KEYTOOL=keytool
fi

command -v python >/dev/null 2>&1 && PY=python || PY=python3
command -v "$PY" >/dev/null 2>&1 || die "python is required (used to assemble the APK)."

echo "AppAlive build"
echo "  sdk          : $SDK"
echo "  build-tools  : $(basename "$BT")"
echo "  platform     : $(basename "$PLATFORM")"
echo "  javac        : $("$JAVAC" -version 2>&1 | head -1)"
echo "  minSdk       : $MIN_SDK"
echo "  targetSdk    : $TARGET_SDK"

# --------------------------------------------------------------- prepare
if [ "$CLEAN" = "1" ]; then rm -rf "$BUILD" "$DIST"; fi
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

# Icons are generated, not committed as opaque binaries.
if [ ! -f "$APP/res/mipmap-xxhdpi/ic_launcher.png" ]; then
    step "generating icons"
    "$PY" "$ROOT/tools/make_icons.py"
fi

# --------------------------------------------------------------- manifest
# The source manifest has no package= attribute so that AGP 8+ accepts it
# (it treats one as a hard error). aapt2 requires it, so inject it here.
step "preparing manifest (injecting package=$PKG)"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'"$PKG"'"|' \
    "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
grep -q "package=\"$PKG\"" "$BUILD/AndroidManifest.xml" \
    || die "package attribute injection failed - did the <manifest> tag change?"

# --------------------------------------------------------------- resources
step "aapt2 compile"
"$AAPT2" compile --dir "$(wpath "$APP/res")" -o "$(wpath "$BUILD/res.zip")"

step "aapt2 link"
"$AAPT2" link \
    -I "$(wpath "$ANDROID_JAR")" \
    --manifest "$(wpath "$BUILD/AndroidManifest.xml")" \
    --java "$(wpath "$BUILD/gen")" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -o "$(wpath "$BUILD/base.apk")" \
    "$(wpath "$BUILD/res.zip")"

# --------------------------------------------------------------- java
step "javac"
: > "$BUILD/sources.txt"
find "$APP/java" "$BUILD/gen" -name '*.java' -print | while read -r f; do
    wpath "$f" >> "$BUILD/sources.txt"
done
SRC_COUNT=$(wc -l < "$BUILD/sources.txt" | tr -d ' ')
[ "$SRC_COUNT" -gt 0 ] || die "no .java sources found"
echo "  $SRC_COUNT source files"

"$JAVAC" \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -nowarn -Xlint:-options \
    -bootclasspath "$(wpath "$ANDROID_JAR")" \
    -d "$(wpath "$BUILD/classes")" \
    "@$(wpath "$BUILD/sources.txt")"

step "jar"
"$JAR" cf "$(wpath "$BUILD/classes.jar")" -C "$(wpath "$BUILD/classes")" .

# --------------------------------------------------------------- dex
step "d8 (desugaring for minSdk $MIN_SDK)"
"$JAVA" -cp "$(wpath "$D8_JAR")" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" \
    --lib "$(wpath "$ANDROID_JAR")" \
    --output "$(wpath "$BUILD/dex")" \
    "$(wpath "$BUILD/classes.jar")"
[ -f "$BUILD/dex/classes.dex" ] || die "d8 produced no classes.dex"

# --------------------------------------------------------------- assemble
step "assembling apk"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
"$PY" "$ROOT/tools/apkadd.py" "$BUILD/unsigned.apk" \
    "classes.dex=$BUILD/dex/classes.dex"

step "zipalign"
"$ZIPALIGN" -f -p 4 "$(wpath "$BUILD/unsigned.apk")" "$(wpath "$BUILD/aligned.apk")"

# --------------------------------------------------------------- sign
if [ ! -f "$KEYSTORE" ]; then
    step "generating a local signing key (first run only)"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair \
        -keystore "$(wpath "$KEYSTORE")" \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=AppAlive, OU=MIS, O=TMC, C=TW" >/dev/null
    echo "  wrote $KEYSTORE"
    echo "  NOTE: this is a local debug-grade key. Keep it - reinstalling over an"
    echo "        existing install requires the same signature."
fi

step "apksigner"
"$JAVA" -jar "$(wpath "$APKSIGNER_JAR")" sign \
    --ks "$(wpath "$KEYSTORE")" \
    --ks-pass "pass:$KEY_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version "$MIN_SDK" \
    --out "$(wpath "$DIST/appalive.apk")" \
    "$(wpath "$BUILD/aligned.apk")"

step "verifying signature"
"$JAVA" -jar "$(wpath "$APKSIGNER_JAR")" verify --verbose \
    "$(wpath "$DIST/appalive.apk")" | sed 's/^/  /'

SIZE=$(wc -c < "$DIST/appalive.apk" | tr -d ' ')
echo ""
echo "OK  dist/appalive.apk  ($SIZE bytes)"
echo "    install with: ./install.sh --targets com.example.one,com.example.two"
