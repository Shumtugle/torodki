#!/bin/sh
# Runs the game on the desktop stand: compiles every source file that does not touch
# the platform, together with the stand, plays a scene and saves snapshots.
#
#   stand/run.sh [scene file] [output dir]      defaults: stand/scene.txt, out/stand
set -e
cd "$(dirname "$0")/.."
SCENE=${1:-stand/scene.txt}
OUT=${2:-out/stand}
rm -rf out/stand-classes
mkdir -p out/stand-classes "$OUT"
# the game side: files with no platform imports
PURE=$(grep -rL -E '^import android\.|[^a-z.]android\.[a-z]+\.[A-Z]' --include='*.java' src)
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out/stand-classes \
        $PURE $(find stand/src -name '*.java') 2>out/stand-javac.log; then
    grep -v JAVA_TOOL_OPTIONS out/stand-javac.log; exit 1
fi
MAIN=$(cd stand/src && find . -name 'Stand.java' | sed 's|^\./||; s|\.java$||; s|/|.|g')
java -Djava.awt.headless=true -cp out/stand-classes "$MAIN" "$SCENE" "$OUT" 2>&1 | grep -v JAVA_TOOL_OPTIONS
