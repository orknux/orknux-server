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
else
    # -Pwith-ui packs whatever orknux-ui/dist holds and builds none, so a bundle older than the interface's newest
    # commit went into two installers unnoticed and the app drew the screens of a week before. Refused here.
    built="$(stat -c %Y ../orknux-ui/dist/index.html 2>/dev/null || echo 0)"
    committed="$(git -C ../orknux-ui log -1 --format=%ct)"
    if [ "$built" -lt "$committed" ]; then
        echo "orknux-ui/dist is older than orknux-ui's last commit: run npm run build in orknux-ui, then package again" >&2
        exit 1
    fi
    if [ -n "${1:-}" ]; then
        cp "$1" resources/server/app.jar
    else
        # The reactor's newest jar, the one -Pwith-ui packs the interface into; never the -plain one, nor the
        # oldest version left in target, which is what the alphabet picked.
        jar="$(ls -t ../app/target/orknux-app-*.jar | grep -v -- '-plain' | head -n 1)"
        cp "$jar" resources/server/app.jar
    fi
fi
echo "server: $(du -h resources/server/app.jar | cut -f1) at resources/server/app.jar"
