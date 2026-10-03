#!/usr/bin/env bash
#
# Does `docker run orknux-one`, with nothing else, give somebody a working Orknux?
#
# `verify-image.sh` beside this asks whether the server image boots when it is
# handed a database, a key and an engine setting. This image's whole claim is
# that it needs none of that: it is started with **no environment, no volume and
# no network**, and everything it needs it has to invent on the way up. So the
# things worth asserting are different ones.
#
#   1. it serves the interface and the API on one port, and describes its own
#      sign-in truthfully - there is no directory in here
#   2. it invented an encryption key, kept it, and did not invent a second one
#      on the next start - the failure this image can most easily have is silent
#   3. it invented an administrator, and that administrator can actually sign in
#   4. a signed-in call reads and writes, so SQLite migrated and works
#   5. an anonymous call is still refused, in the packaged image
#   6. a restart is the same installation: same key, same account, same data
#   7. non-root, the start loop as PID 1, and it stops within the grace period
#   8. it updates itself in place, rolls back, and refuses a tampered jar (#584)
#
# Point 3 is the one to keep. A password that is generated, printed and then does
# not work is indistinguishable from an image that is fine until somebody tries
# it - and nobody tries it in a build.
#
# Usage:  scripts/verify-one-image.sh [image-tag]
# Exits non-zero, loudly, on the first thing that is not true.

set -euo pipefail

IMAGE="${1:-orknux-one:verify}"
APP="orknux-one-verify"
VOLUME="orknux-one-verify-data"
# Not 8080. The machine running this may well have an Orknux on it already, and
# a port clash reads as an image that does not work.
PORT="18099"
BASE="http://localhost:$PORT"

JAR="$(mktemp -d)/cookies.txt"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '  \033[32mok\033[0m   %s\n' "$*"; }
die() { printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  if [ "${KEEP:-}" = "1" ]; then
    printf '\nKEEP=1, leaving %s running on %s\n' "$APP" "$BASE"
    return
  fi
  docker rm -f "$APP" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  rm -rf "$(dirname "$JAR")"
}
trap cleanup EXIT

# Whatever the last run left behind. A verify that passes because a container
# from an hour ago is still answering has verified an hour-old image.
docker rm -f "$APP" >/dev/null 2>&1 || true
docker volume rm "$VOLUME" >/dev/null 2>&1 || true

say "Building $IMAGE"
docker build -f Dockerfile.one -t "$IMAGE" .

# The volume is named rather than anonymous, and it is the *only* argument. It
# is not configuration: it exists so this script can stop the container, start it
# again and ask whether the second start found what the first one left. A
# person running this image gets the same thing from an anonymous volume, or
# from `-v somewhere:/var/lib/orknux`.
say "Starting it with nothing supplied"
docker run -d --name "$APP" -p "$PORT:8080" -v "$VOLUME:/var/lib/orknux" "$IMAGE" >/dev/null

wait_for_it() {
  local seconds="$1" waited=0
  while [ "$waited" -lt "$seconds" ]; do
    if curl -fsS -m 3 "$BASE/api/auth/method" >/dev/null 2>&1; then return 0; fi
    # A container that died is not going to start answering.
    if [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" != "true" ]; then
      docker logs "$APP" 2>&1 | tail -40
      die "The container exited while starting"
    fi
    sleep 2
    waited=$(( waited + 2 ))
  done
  docker logs "$APP" 2>&1 | tail -40
  die "It never answered within ${seconds}s"
}

# 1. One port, two halves.
#
# Since #585 there is no nginx in here: the server serves the bundle from its own
# jar. `/api/auth/method` is the one endpoint open by design, since the sign-in
# screen has to read it before anybody has signed in, and `/` below is the page.
# Both on the same port is the whole point: the browser stays on one origin, so
# the session cookie is first-party.
say "Waiting for it to answer"
wait_for_it 180
ok "The API answers on $PORT"

# What it says about signing in, which is the first thing anybody sees.
#
# There is no directory in this container. Under the LDAP default the card said
# there was: "Authenticating via LDAP", with an offer of single sign-on, on an
# image that has nothing listening on 389. It let the generated administrator in
# anyway - internal accounts are checked first - so it worked and looked broken,
# which is the worst of both. INTERNAL is the entrypoint saying what is actually
# here, and nothing else in this script would notice it going back.
method="$(curl -fsS -m 10 "$BASE/api/auth/method")" || die "The sign-in method could not be read"
case "$method" in
  *'"method":"INTERNAL"'*) ok "It describes sign-in as INTERNAL" ;;
  *'"method":"LDAP"'*) die "It claims LDAP, and there is no directory in this image: $method" ;;
  *) die "The sign-in method is not one this image should report: $method" ;;
esac

body="$(curl -fsS -m 10 "$BASE/")" || die "The interface did not answer on /"
case "$body" in
  *"<div id=\"root\""*|*"<div id=root"*) ok "The interface is served on the same port" ;;
  *) die "/ answered, but with something that is not the interface bundle" ;;
esac

# A route the bundle draws rather than a file on disk. Without the single-page
# fallback this 404s, which is what a bookmarked run page would do.
code="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/workspace/1/executions")"
[ "$code" = "200" ] || die "A deep link answered $code; the single-page fallback is not working"
ok "A deep link is answered by the bundle"

# 2. The key. The one that loses data if it is wrong.
say "Checking the encryption key"
key="$(docker exec "$APP" cat /var/lib/orknux/secret.key)" || die "No key was written to the data directory"
# 32 bytes, base64: 44 characters. A key of another length fails when a
# credential is read rather than when the server starts, which is a fortnight
# later and in somebody else's log.
[ "${#key}" = "44" ] || die "The generated key is ${#key} characters; 32 bytes of base64 is 44"
mode="$(docker exec "$APP" stat -c '%a' /var/lib/orknux/secret.key)"
[ "$mode" = "600" ] || die "The key file is mode $mode; it should be readable only by the user that wrote it"
ok "A 32-byte key was generated and kept at /var/lib/orknux/secret.key, mode $mode"

# 3. The administrator. Generated, printed, and - the part worth testing - real.
say "Checking that the generated administrator can sign in"
password="$(docker exec "$APP" cat /var/lib/orknux/admin-password)" || die "No administrator password was written"
[ "${#password}" -ge 12 ] || die "The generated password is ${#password} characters, under the 12 this product requires of any password"

# It has to be in the log as well as in the file. Somebody starting this image in
# the foreground is told the password there and nowhere else, and a banner that
# stops being printed is a lockout nobody notices until they are locked out.
#
# Read into a variable rather than piped into `grep -q`. Under `pipefail` that
# pipeline reports the *producer's* death rather than the match: `grep -q` exits
# the moment it finds the password, `docker logs` is left writing into a closed
# pipe, and the 141 it dies with becomes the status of the whole pipeline. A
# password printed perfectly well is then reported as never printed. It only
# bites once the log outgrows the pipe buffer, which is why this passed for a
# fortnight and then failed on a release: the server says more on the way up
# than it used to.
logged="$(docker logs "$APP" 2>&1)"
case "$logged" in
  *"$password"*) ;;
  *)
    # What it did say, so the next person does not have to reproduce it.
    printf '%s
' "$logged" | tail -40
    die "The generated password is in the file but was never printed; nobody running this in the foreground would know it"
    ;;
esac
ok "A ${#password}-character password was generated and printed"

signed_in="$(curl -fsS -m 15 -X POST "$BASE/api/session" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$password\"}" \
  -c "$JAR")" || die "The generated administrator could not sign in"

case "$signed_in" in
  *'"admin":true'*) ok "admin signs in and is an administrator" ;;
  *) die "admin signed in but is not an administrator: $signed_in" ;;
esac

# 4. It reads and writes. Signing in already proved a read - the account came out
#    of the database - so this writes, because a schema that migrated and a
#    database that accepts an insert are not the same claim under SQLite.
say "Checking that it reads and writes"
created="$(curl -fsS -m 15 -X POST "$BASE/graphql" -H 'content-type: application/json' -b "$JAR" \
  -d '{"query":"mutation { createWorkspace(input: {name: \"verify\", description: \"written by verify-one-image.sh\"}) { id name } }"}')" \
  || die "The GraphQL call failed outright"
case "$created" in
  *'"errors"'*) die "Creating a workspace failed: $created" ;;
  *'"name":"verify"'*) ok "A workspace was created and read back" ;;
  *) die "Creating a workspace answered something unexpected: $created" ;;
esac

# And what the monitoring screen makes of an installation with no directory.
#
# Asked here because it needs the administrator's session, which the step above
# already has. It used to answer DEGRADED, "cannot reach directory", having
# probed the localhost:389 that `spring.ldap.urls` falls back to when nobody sets
# it - a red card, permanently, for a component this image was never meant to
# have. A dependency that was never configured is absent, not unreachable, so
# there is no card for it at all.
health="$(curl -fsS -m 15 -X POST "$BASE/graphql" -H 'content-type: application/json' -b "$JAR" \
  -d '{"query":"{ components { status detail dependencies { name } } }"}')" \
  || die "The monitoring query failed outright"
case "$health" in
  *'"errors"'*) die "Reading the components failed: $health" ;;
  *'"name":"Directory"'*) die "It reports a directory this image has not got: $health" ;;
  *'"status":"DEGRADED"'*) die "It reports itself degraded with nothing missing: $health" ;;
  *'"status":"HEALTHY"'*) ok "Monitoring is healthy, with no card for an absent directory" ;;
  *) die "The components query answered something unexpected: $health" ;;
esac

# 5. Security is on in the packaged image, not only in the dev profile. The same
#    check `verify-image.sh` makes, and worth making twice: this image is the one
#    somebody leaves running on a machine they share.
say "Checking that it refuses anonymous callers"
code="$(curl -s -o /dev/null -w '%{http_code}' -m 10 -X POST "$BASE/graphql" \
  -H 'content-type: application/json' -d '{"query":"{ workspaces { content { id } } }"}')"
[ "$code" = "401" ] || die "An anonymous GraphQL call answered $code, expected 401"
ok "Anonymous calls are refused"

# 6. Non-root and PID 1, before the restart, because a container that has been
#    stopped cannot be asked.
say "Checking the user and PID 1"
who="$(docker exec "$APP" id -un)"
[ "$who" = "orknux" ] || die "Running as $who, expected orknux"
ok "Runs as $who"

# PID 1 is the start loop since #584, not the JVM: it has to outlive a server
# that exits to be started on another release. It hands SIGTERM on, which the
# graceful stop below is what proves.
pid1="$(docker exec "$APP" cat /proc/1/cmdline | tr '\0' ' ')"
case "$pid1" in
  *orknux-run*) ok "PID 1 is the start loop: $pid1" ;;
  *) die "PID 1 is '$pid1', not the start loop - nothing will hand docker stop's signal to the JVM" ;;
esac
docker exec "$APP" sh -c 'for p in /proc/[0-9]*; do tr "\0" " " < $p/cmdline; echo; done' | grep -q -- '-jar' \
  || die "No JVM is running under the start loop"
ok "The JVM runs under it"

# 7. A restart is the same installation.
#
# This is the assertion the whole data directory exists for. A second start that
# generated a second key would boot, report itself healthy, and quietly make
# every stored credential unreadable; nothing about that is visible from
# outside, so it is asked here.
say "Restarting, and checking it is the same installation"
docker restart -t 30 "$APP" >/dev/null
wait_for_it 180

restarted_key="$(docker exec "$APP" cat /var/lib/orknux/secret.key)"
[ "$restarted_key" = "$key" ] || die "The key changed across a restart - every credential stored before it is now unreadable"
ok "The same encryption key"

curl -fsS -m 15 -X POST "$BASE/api/session" -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$password\"}" -c "$JAR" >/dev/null \
  || die "The administrator could not sign in after a restart"
ok "The same administrator"

after="$(curl -fsS -m 15 -X POST "$BASE/graphql" -H 'content-type: application/json' -b "$JAR" \
  -d '{"query":"{ workspaces { content { name } } }"}')"
case "$after" in
  *'"name":"verify"'*) ok "The workspace created before the restart is still there" ;;
  *) die "The data did not survive the restart: $after" ;;
esac

# 8. It stops when asked. Anything slower than the grace period is a container
#    that gets killed on every deploy, mid-request.
say "Checking that it stops gracefully"
began="$(date +%s)"
docker stop -t 30 "$APP" >/dev/null
took=$(( $(date +%s) - began ))
[ "$took" -lt 30 ] || die "It did not stop within the grace period; it was killed"

# 143 is 128+SIGTERM, which is what a JVM reports after `docker stop` even when
# its shutdown hooks ran to completion. 137 is 128+SIGKILL: the daemon losing
# patience at the end of the grace period, which means shutdown did not finish.
code="$(docker inspect -f '{{.State.ExitCode}}' "$APP")"
case "$code" in
  0|143) ok "Stopped in ${took}s, exit $code" ;;
  137) die "It was killed after the grace period - shutdown did not finish" ;;
  *) die "It exited $code, which is neither a clean stop nor a signal" ;;
esac

# 9. An update in place, a rollback, and a tampered jar refused at start-up. #584.
#
# A container of its own, because this one is handed something: the TEST ONLY
# pair that trusts a key made here, which the "nothing supplied" container above
# must never have. See scripts/self-update/lib.sh for what is asserted and why.
UPDATE_APP="orknux-one-verify-update"
UPDATE_VOLUME="orknux-one-verify-update-data"
UPDATE_PORT="18098"
UPDATE_WORK="${SELF_UPDATE_WORK:-$(mktemp -d)}"
cleanup_update() {
  if [ "${KEEP:-}" != "1" ]; then
    docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true
    docker volume rm "$UPDATE_VOLUME" >/dev/null 2>&1 || true
    rm -rf "$UPDATE_WORK"
  fi
  cleanup
}
trap cleanup_update EXIT
docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true
docker volume rm "$UPDATE_VOLUME" >/dev/null 2>&1 || true

# shellcheck source=self-update/lib.sh
. "$(dirname "$0")/self-update/lib.sh"
say "Preparing a release to update to"
self_update_prepare "$IMAGE" "$UPDATE_WORK"

mapfile -t trust < <(self_update_trust_args "$UPDATE_WORK")
# Any arguments are handed to docker run: #593 starts it again with a pin.
start_update_app() {
  docker rm -f "$UPDATE_APP" >/dev/null 2>&1 || true
  docker run -d --name "$UPDATE_APP" -p "$UPDATE_PORT:8080" -v "$UPDATE_VOLUME:/var/lib/orknux" "${trust[@]}" "$@" "$IMAGE" >/dev/null
}
start_update_app
APP="$UPDATE_APP" BASE="http://localhost:$UPDATE_PORT" wait_for_it 180
case "$(docker logs "$UPDATE_APP" 2>&1)" in
  *"TEST ONLY"*) ok "It says, loudly, that it trusts a test key" ;;
  *) die "A container trusting a test key did not say so" ;;
esac
update_password="$(docker exec "$UPDATE_APP" cat /var/lib/orknux/admin-password)"
orknux_uid="$(docker exec "$UPDATE_APP" id -u)"

# The database is a file in the volume, so it is changed with the container
# stopped, as the user the image runs as - a journal left behind owned by
# anybody else would be a database the server can no longer write.
tamper_one() {
  docker run --rm --user "$orknux_uid" -v "$UPDATE_VOLUME:/var/lib/orknux" -v "$UPDATE_WORK:/work" \
    -v "$SELF_UPDATE_TOOLS:/tools:ro" -w /work "$SELF_UPDATE_JDK" \
    java -cp 'BOOT-INF/lib/*' /tools/Tamper.java jdbc:sqlite:/var/lib/orknux/orknux.db >/dev/null
}
self_update_run "$UPDATE_APP" "http://localhost:$UPDATE_PORT" admin "$update_password" "$UPDATE_WORK" tamper_one
self_update_fallbacks "$UPDATE_WORK" start_update_app
self_update_mounted_dir start_update_app

printf '\n\033[32morknux-one works with nothing supplied, and updates itself in place.\033[0m\n'
