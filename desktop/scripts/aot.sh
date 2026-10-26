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

# The release jar is signed, and extract carries its signature files into app.jar under a manifest it rewrote, so
# the JVM refused to start it: "Invalid signature file digest for Manifest main attributes" (1.2.2's desktop build).
# The signature belongs to resources/server/app.jar, which is kept and is what the launcher checks; the copy made
# here is unpacked from it and drops it.
PYTHON=""
for candidate in python3 python; do
    if "$candidate" -c 'import zipfile' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[ -n "$PYTHON" ] || { echo "no python to strip the signature from the unpacked jar" >&2; exit 1; }
"$PYTHON" - "$(native "$PWD/resources/server/app/app.jar")" <<'EOF'
import os, re, sys, zipfile
path = sys.argv[1]
signed = re.compile(r'^META-INF/[^/]+\.(SF|RSA|DSA|EC)$', re.IGNORECASE)
with zipfile.ZipFile(path) as source:
    if not any(signed.match(one.filename) for one in source.infolist()):
        sys.exit(0)
    with zipfile.ZipFile(path + '.unsigned', 'w') as target:
        for one in source.infolist():
            if not signed.match(one.filename):
                target.writestr(one, source.read(one.filename), compress_type=one.compress_type)
os.replace(path + '.unsigned', path)
EOF

# Whole seconds, before the cache records them: it compares a jar's time to the tick, and the time put back after an
# install (see aot-times.tsv below) is written in seconds - a fraction left here was a cache refused on every start.
find resources/server/app -name '*.jar' -type f | while IFS= read -r jar; do touch -d "@$(stat -c %Y "$jar")" "$jar"; done

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

# The cache refuses any jar whose timestamp moved, and an installer stamps every file with the time it was installed:
# the 1.2.0 desktop started without it for exactly that. So the times are written down here, and server.js puts them back.
(cd resources/server/app && find . -name '*.jar' -type f -printf '%T@\t%P\n' | sed 's/\.[0-9]*\t/\t/' > aot-times.tsv)
[ -s resources/server/app/aot-times.tsv ] || { echo "no jar times were written" >&2; exit 1; }
echo "aot: $(du -sh resources/server/app | cut -f1) at resources/server/app, cache $(du -h resources/server/app/app.aot | cut -f1)"
