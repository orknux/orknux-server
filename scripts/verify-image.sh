#!/usr/bin/env bash
#
# Does the image actually run?
#
# The build proves the code compiles and the suite proves it behaves. Neither
# says anything about the artefact that gets published: the Dockerfile skips
# tests deliberately, and CI pushed the image without ever starting it. An image
# that cannot boot — a missing entrypoint, a jar that moved, a JRE without
# something the app needs, a non-root user that cannot read its own file — is
# built, pushed and tagged exactly like one that can.
#
# So this starts it, against a real Postgres, and asserts the things that are
# true of the *container* rather than of the application:
#
#   1. it boots and serves
#   2. Flyway migrated the schema it was given
#   3. security is enforced in the packaged image, not only in the dev profile
#   4. it serves the interface, to somebody not yet signed in
#   5. it runs as a non-root user, as the Dockerfile intends
#   6. the start loop is PID 1, and hands `docker stop` on to the JVM
#   7. it stops within the grace period rather than being killed
#   8. it updates itself in place, rolls back, and refuses a tampered jar (#584)
#   9. it honours ORKNUX_RELEASE_PIN, and a jar that cannot start - pinned or
#      chosen - ends on the image's own jar without the container exiting (#593)
#
# What it does not cover: Temporal. The image is started with the inline engine,
# because whether a separate service is reachable is not a property of this
# artefact. Worth knowing while reading a green run — with the default
# configuration the application *refuses to start* when Temporal is not up
# (`TemporalWorkerLifecycle` throws and the context fails), which is deliberate,
# and which means a deployment brought up before its Temporal will restart until
# that service answers.
#
# Usage:  scripts/verify-image.sh [image-tag]
# Exits non-zero, loudly, on the first thing that is not true.

set -euo pipefail

IMAGE="${1:-orknux-server:verify}"
NET="orknux-verify-net"
DB="orknux-verify-db"
APP="orknux-verify-app"
PORT="18080"

# A key of the right shape. Nothing here is kept, so it may be anything valid;
# a wrong one would fail the credential checks rather than the boot.
SECRET_KEY="$(head -c 32 /dev/urandom | base64)"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '  \033[32mok\033[0m   %s\n' "$*"; }
die() { printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  # Logs first: a failure that takes its evidence with it is a failure nobody
  # can fix from a CI transcript.
  if [ "${KEEP:-}" = "1" ]; then
    printf '\nKEEP=1, leaving %s and %s running\n' "$APP" "$DB"
    return
  fi
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "Building $IMAGE"
docker build -t "$IMAGE" .

say "Starting Postgres"
docker network create "$NET" >/dev/null 2>&1 || true
docker run -d --name "$DB" --network "$NET" \
  -e POSTGRES_DB=orknux -e POSTGRES_USER=orknux -e POSTGRES_PASSWORD=orknux \
  postgres:18 >/dev/null

# Ready means ready over TCP, asked twice in a row. The image's first start runs
# a temporary server on the socket only, answers pg_isready from it, then stops
# it to start the real one - so one success on the socket can land just before
# the restart, and the next command meets a database that is not there.
ready=0
for _ in $(seq 1 60); do
  if docker exec "$DB" pg_isready -h 127.0.0.1 -U orknux -d orknux >/dev/null 2>&1; then
    ready=$((ready + 1))
    [ "$ready" -ge 2 ] && break
  else
    ready=0
  fi
  sleep 1
done
[ "$ready" -ge 2 ] || die "Postgres never became ready"
ok "Postgres is up"

say "Starting the image"
docker run -d --name "$APP" --network "$NET" -p "$PORT:8080" \
  -e ORKNUX_DB_URL="jdbc:postgresql://$DB:5432/orknux" \
  -e ORKNUX_SECRET_KEY="$SECRET_KEY" \
  -e ORKNUX_TEMPORAL_ENABLED=false \
  "$IMAGE" >/dev/null

# 1. It boots and serves.
#
# Asked of `/api/auth/method`, which is open by design — the sign-in screen has
# to read it before anybody has signed in. `/actuator/health` needs a session,
# so an unauthenticated probe of it proves nothing about booting.
say "Waiting for it to answer"
started=""
for _ in $(seq 1 90); do
  if curl -fsS "http://localhost:$PORT/api/auth/method" >/dev/null 2>&1; then started="yes"; break; fi
  # A container that died is not going to start answering, so say so now
  # rather than after ninety seconds of politeness.
  if [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" != "true" ]; then
    docker logs "$APP" 2>&1 | tail -40
    die "The container exited while starting"
  fi
  sleep 1
done
[ -n "$started" ] || { docker logs "$APP" 2>&1 | tail -40; die "It never answered within 90s"; }
ok "It boots and serves"

# 2. Flyway ran. The schema is what the application is useless without, and a
#    migration that fails leaves the app up and every query broken.
say "Checking the schema"
version="$(docker exec "$DB" psql -U orknux -d orknux -tAc \
  "SELECT max(version::numeric) FROM flyway_schema_history WHERE success" 2>/dev/null || true)"
[ -n "$version" ] || die "No flyway_schema_history — the migrations did not run"
failed="$(docker exec "$DB" psql -U orknux -d orknux -tAc \
  "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
[ "$failed" = "0" ] || die "$failed migrations failed"
ok "Schema migrated to v$version, nothing failed"

# 3. Security is on in the packaged image. A profile that only guards the dev
#    run would be found here rather than by somebody else.
say "Checking that it refuses anonymous callers"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/graphql" \
  -H 'content-type: application/json' -d '{"query":"{ workspaces { content { id } } }"}')"
[ "$code" = "401" ] || die "An anonymous GraphQL call answered $code, expected 401"
ok "Anonymous calls are refused"

# 4. The interface. Since #585 it is built into the jar with -Pwith-ui and served
#    by the server, so an image built without the profile — or with an empty
#    bundle — boots, migrates, refuses strangers and serves nobody a page. Every
#    check above passes on that image; these are what notice. Asked anonymously,
#    because the sign-in page has to load before anybody has signed in.
say "Checking that it serves the interface"
headers="$(mktemp)"
page="$(curl -fsS -D "$headers" "http://localhost:$PORT/")" \
  || die "GET / did not answer 200 — the image serves no interface"
grep -qi '<html' <<<"$page" || die "GET / answered, but not with a page"
grep -qi '^cache-control:.*no-cache' "$headers" \
  || die "GET / is cacheable — a browser would keep the previous bundle after an upgrade"
ok "/ is the page, and is not cached"

# The page names its own hashed assets; one of them has to come back, and be
# kept, or the page is a blank screen that looked fine from curl.
asset="$(grep -oE '/assets/[^"]+' <<<"$page" | head -n 1 || true)"
[ -n "$asset" ] || die "The page names no /assets/ file — the bundle in the jar is not a build"
code="$(curl -s -o /dev/null -D "$headers" -w '%{http_code}' "http://localhost:$PORT$asset")"
[ "$code" = "200" ] || die "GET $asset answered $code"
grep -qi '^cache-control:.*max-age=31536000' "$headers" \
  || die "GET $asset is not cached for a year, though its name is its hash"
ok "$asset is served and kept"

# A route the page draws, typed into the address bar, is the page; a path the
# server owns is not, or a mistyped API call would answer with a sign-in screen.
deep="$(curl -fsS "http://localhost:$PORT/workspace/1/workflows")" \
  || die "A deep link did not answer 200 — reloading any page but / would fail"
[ "$deep" = "$page" ] || die "A deep link answered something other than the page"
code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/no-such-thing")"
[ "$code" != "200" ] || die "An unknown /api path answered 200 — the page is standing in for the API"
ok "Deep links get the page; /api does not ($code)"
rm -f "$headers"

# 5. Not root. The Dockerfile says so; this checks the image was built the way
#    it reads.
say "Checking the user"
who="$(docker exec "$APP" id -un)"
[ "$who" = "orknux" ] || die "Running as $who, expected orknux"
ok "Runs as $who"

# 6. PID 1 is the start loop (#584), which outlives a server that exits to be
#    started on another release, and hands SIGTERM on to the JVM - without that
#    `docker stop` would stop at the shell and the JVM would be killed at the end
#    of the grace period. The graceful stop below is what proves the handing on.
say "Checking PID 1"
pid1="$(docker exec "$APP" cat /proc/1/cmdline | tr '\0' ' ')"
case "$pid1" in
  *orknux-run*) ok "PID 1 is the start loop: $pid1" ;;
  *) die "PID 1 is '$pid1', not the start loop — nothing will hand docker stop's signal to the JVM" ;;
esac

# 7. It stops when asked. Anything slower than the grace period is a container
#    that gets killed on every deploy, mid-request.
say "Checking that it stops gracefully"
began="$(date +%s)"
docker stop -t 30 "$APP" >/dev/null
took=$(( $(date +%s) - began ))
[ "$took" -lt 30 ] || die "It did not stop within the grace period; it was killed"

# 143 is 128+SIGTERM, which is what a JVM reports after `docker stop` even when
# its shutdown hooks ran to completion — the signal is how it was asked to stop,
# not evidence that anything went wrong. What would be evidence is 137
# (128+SIGKILL): that is the daemon losing patience at the end of the grace
# period, which means shutdown did not finish and requests in flight were cut.
code="$(docker inspect -f '{{.State.ExitCode}}' "$APP")"
case "$code" in
  0|143) ok "Stopped in ${took}s, exit $code" ;;
  137) die "It was killed after the grace period — shutdown did not finish" ;;
  *) die "It exited $code, which is neither a clean stop nor a signal" ;;
esac

# 7. An update in place, a rollback, and a tampered jar refused at start-up,
#    on Postgres. #584. A second container on the same database, handed the
#    TEST ONLY trust of a key made here and an administrator to sign in as; see
#    scripts/self-update/lib.sh for what is asserted and why.
UPDATE_APP="orknux-verify-update"
UPDATE_WORK="${SELF_UPDATE_WORK:-$(mktemp -d)}"
UPDATE_PASSWORD="verify-$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
cleanup_update() {
  if [ "${KEEP:-}" != "1" ]; then
    docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true
    rm -rf "$UPDATE_WORK"
  fi
  cleanup
}
trap cleanup_update EXIT
docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true

# shellcheck source=self-update/lib.sh
. "$(dirname "$0")/self-update/lib.sh"
say "Preparing a release to update to"
self_update_prepare "$IMAGE" "$UPDATE_WORK"

mapfile -t trust < <(self_update_trust_args "$UPDATE_WORK")
# Any arguments are handed to docker run: #593 starts it again with a pin.
start_update_app() {
  docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true
  docker run -d --name "$UPDATE_APP" --network "$NET" -p "$PORT:8080" \
    -e ORKNUX_DB_URL="jdbc:postgresql://$DB:5432/orknux" \
    -e ORKNUX_SECRET_KEY="$SECRET_KEY" \
    -e ORKNUX_TEMPORAL_ENABLED=false \
    -e ORKNUX_AUTH_METHOD=INTERNAL \
    -e ORKNUX_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e ORKNUX_BOOTSTRAP_ADMIN_PASSWORD="$UPDATE_PASSWORD" \
    "${trust[@]}" "$@" "$IMAGE" >/dev/null
}
start_update_app
for _ in $(seq 1 120); do
  curl -fsS "http://localhost:$PORT/api/auth/method" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "http://localhost:$PORT/api/auth/method" >/dev/null 2>&1 || { docker logs "$UPDATE_APP" 2>&1 | tail -40; die "The update container never answered"; }

tamper_postgres() {
  docker run --rm --network "$NET" -v "$UPDATE_WORK:/work" -v "$SELF_UPDATE_TOOLS:/tools:ro" -w /work "$SELF_UPDATE_JDK" \
    java -cp 'BOOT-INF/lib/*' /tools/Tamper.java "jdbc:postgresql://$DB:5432/orknux" orknux orknux >/dev/null
}
self_update_run "$UPDATE_APP" "http://localhost:$PORT" admin "$UPDATE_PASSWORD" "$UPDATE_WORK" tamper_postgres
self_update_fallbacks "$UPDATE_WORK" start_update_app

printf '\n\033[32mThe image works, and updates itself in place.\033[0m\n'
