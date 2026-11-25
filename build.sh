#!/bin/bash
# Builds Best Seat Helper without Gradle, directly with the Android build tools.
# Output: build/bestseat-helper.apk
# Override JAVA_HOME, ANDROID_SDK, BUILD_TOOLS or KEYSTORE / KEYSTORE_PASS if your setup differs.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
OUT="$DIR/build"
KEYSTORE="${KEYSTORE:-$DIR/debug.keystore}"
PASS="${KEYSTORE_PASS:-android}"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# Create the signing key once and always reuse it,
# otherwise the app can't be installed over an older version.
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" \
    -alias tvhelper -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Best Seat Helper" >/dev/null
fi

"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/res.zip"
"$BT/aapt2" link --manifest "$DIR/AndroidManifest.xml" -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 2.0.0 \
  -o "$OUT/unsigned.apk" "$OUT/res.zip"

find "$DIR/src" -name '*.java' -exec \
  javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -Xlint:-options \
  -d "$OUT/classes" {} +

find "$OUT/classes" -name '*.class' -exec \
  "$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" {} +

(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$OUT/bestseat-helper.apk" "$OUT/aligned.apk"

echo "Done: $OUT/bestseat-helper.apk"
