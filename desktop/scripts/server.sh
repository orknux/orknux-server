#!/bin/sh
# Puts the server jar where the package expects it, resources/server/app.jar.
#
#   scripts/server.sh                     the jar the reactor just built (app/target)
#   scripts/server.sh <path to a jar>     that jar
#   scripts/server.sh --image <tag>       the jar inside orknux/orknux-one:<tag>, for trying the app without a build
set -eu
cd "$(dirname "$0")/.."
mkdir -p resources/server
if [ "${1:-}" = "--image" ]; then
    id="$(docker create "orknux/orknux-one:${2:?give a tag}")"
    docker cp "$id:/app/app.jar" resources/server/app.jar
    docker rm "$id" >/dev/null
elif [ -n "${1:-}" ]; then
    cp "$1" resources/server/app.jar
else
    # The reactor's own jar, the one -Pwith-ui packs the interface into; never the -plain one.
    jar="$(ls ../app/target/orknux-app-*.jar | grep -v -- '-plain' | head -n 1)"
    cp "$jar" resources/server/app.jar
fi
echo "server: $(du -h resources/server/app.jar | cut -f1) at resources/server/app.jar"
