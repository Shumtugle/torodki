#!/bin/sh
# Builds and signs the APK with the platform tools alone: no IDE, no build system.
#
#   ANDROID_JAR                    platform jar to compile against (API 34)
#   KEYSTORE, KEY_ALIAS, KS_PASS   the signing key
#
# The last line printed is the path of the signed APK.
set -e
NAME=torodki
cd "$(dirname "$0")"
AJ=${ANDROID_JAR:?set ANDROID_JAR to a platform jar}
: "${KEYSTORE:?set KEYSTORE}" "${KEY_ALIAS:?set KEY_ALIAS}" "${KS_PASS:?set KS_PASS}"

rm -rf out
mkdir -p out/gen out/classes out/dex

# resources and manifest; R.java goes to out/gen
ASSETS=""
[ -d assets ] && ASSETS="-A assets"
aapt package -f -M AndroidManifest.xml -S res $ASSETS -I "$AJ" -J out/gen -F out/base.apk

# Java 8 language level: the platform jar has no lambda factory
javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" \
    -d out/classes $(find src out/gen -name '*.java')

# whichever dexer the tools provide
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 26 --lib "$AJ" --output out/dex $(find out/classes -name '*.class')
elif command -v dx >/dev/null 2>&1; then
    dx --dex --min-sdk-version=26 --output=out/dex/classes.dex out/classes
else
    dalvik-exchange --dex --min-sdk-version=26 --output=out/dex/classes.dex out/classes
fi

cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KS_PASS \
    --out "out/$NAME.apk" out/aligned.apk
apksigner verify "out/$NAME.apk"
echo "out/$NAME.apk"
