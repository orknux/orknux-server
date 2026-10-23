#!/bin/sh
# A quicker start for the jar in resources/server: unpacked beside it, with an
# AOT cache made by the runtime in resources/jre. Issue #650.
#
#   scripts/aot.sh          after scripts/server.sh and scripts/runtime.sh
#
# Measured on Windows (Ryzen 9 7900, warm database), from launch to the first
# answer: about 18 s for the jar as it ships, 15 s unpacked, 8.5 s unpacked
# with the cache. The jar's nested libraries are slower to load than plain ones,
# and the cache holds the classes the start loads and links, already parsed.
#
# The cache is only good for the runtime that made it and for the very files it
# was made from - the same names, sizes and modification times - which is why it
# is made here, per platform, with the runtime the package ships, and why the
# server is started from inside resources/server/app with a relative `-jar`:
# that is what lets the cache follow the package to wherever it is installed.
# Where it does not match, the JVM says so in the server's log and starts
# without it; the unpacked jar is still the quicker one. server.js chooses.
#
# Made by starting the server once on a throwaway SQLite database with
# spring.context.exit=onRefresh, which stops it as soon as every bean is built,
# before anything listens or runs. That start is slow - minutes, not seconds -
# because the JVM records what it loads; it is paid here, once, not by whoever
# installs the package. resources/server/app.jar is kept: it is the jar the
# release launcher and the update screen read the version and schema floor from.
set -eu
cd "$(dirname "$0")/.."
JAVA="$PWD/resources/jre/bin/java"
[ -f "$JAVA" ] || JAVA="$PWD/resources/jre/bin/java.exe"
# A path the JVM can open: under Git Bash on Windows, /tmp means nothing to it.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
[ -f resources/server/app.jar ] || { echo "no resources/server/app.jar; run scripts/server.sh first" >&2; exit 1; }
[ -f "$JAVA" ] || { echo "no runtime in resources/jre; run scripts/runtime.sh first" >&2; exit 1; }

rm -rf resources/server/app
"$JAVA" -Djarmode=tools -jar resources/server/app.jar extract \
    --destination resources/server/app --application-filename app.jar

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/tmp" "$scratch/attachments"
there="$(native "$scratch")"

# What server.js starts it with, minus what only the desktop has: the cache
# records the options that shape the heap, and is refused under different ones.
(
    cd resources/server/app
    ORKNUX_DB_URL="jdbc:sqlite:$there/orknux.db" \
    ORKNUX_SECRET_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')" \
    ORKNUX_TEMPORAL_ENABLED=false \
    ORKNUX_AUTH_METHOD=INTERNAL \
    ORKNUX_ATTACHMENTS_LOCATION="$there/attachments" \
    ORKNUX_DATA="$there" \
    ORKNUX_PORT=0 \
    "$JAVA" \
        -XX:MaxRAMPercentage=25 \
        -XX:+ExitOnOutOfMemoryError \
        "-Djdk.nio.maxCachedBufferSize=$((256 * 1024))" \
        "-Djava.io.tmpdir=$there/tmp" \
        -XX:AOTCacheOutput=app.aot \
        -Xlog:aot=warning -Xlog:cds=off \
        -Dspring.context.exit=onRefresh \
        -jar app.jar >"$scratch/training.log" 2>&1
) || { tail -n 50 "$scratch/training.log" >&2; echo "the training start failed" >&2; exit 1; }

[ -s resources/server/app/app.aot ] || { tail -n 50 "$scratch/training.log" >&2; echo "no AOT cache was written" >&2; exit 1; }
echo "aot: $(du -sh resources/server/app | cut -f1) at resources/server/app, cache $(du -h resources/server/app/app.aot | cut -f1)"
