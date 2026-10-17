#!/bin/sh
# The Java runtime the app ships, made with jlink from a Temurin 25 JDK: the
# modules the orknux-one image's JRE carries, so the server meets the same Java
# on a desktop as in the container - Graal's compiler included, which is what
# keeps a workspace's JavaScript fast - minus the debug symbols, man pages and
# headers nothing here reads.
#
#   scripts/runtime.sh [JDK home]     (default: $JAVA_HOME)
#
# Writes resources/jre. CI runs it on each platform's own runner, since a
# runtime is per operating system; scripts/runtime.ps1 is the Windows spelling.
set -eu
cd "$(dirname "$0")/.."
JDK="${1:-${JAVA_HOME:?give a JDK home or set JAVA_HOME}}"
MODULES="$(tr -d ' \n' < scripts/modules.txt)"
rm -rf resources/jre
"$JDK/bin/jlink" \
    --add-modules "$MODULES" \
    --strip-debug --no-man-pages --no-header-files \
    --compress zip-6 \
    --output resources/jre
echo "runtime: $(du -sh resources/jre | cut -f1) at resources/jre"
