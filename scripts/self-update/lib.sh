# Server updates in a real container, end to end. Issue #584. Sourced by
# verify-image.sh and verify-one-image.sh, which own the container, the
# database and the administrator; this owns the jars and the assertions.
#
# The start-up path is the riskiest thing the update feature adds: a loop as
# PID 1, a launcher reading jars out of the database, signals handed on by hand.
# None of it is reachable from the test suite, so this drives it for real:
#
#   1. a second jar - the image's own, with another Implementation-Version -
#      signed with a key made here, and trusted through the TEST ONLY pair
#      ORKNUX_RELEASE_TEST / ORKNUX_RELEASE_TRUST_EXTRA
#   2. uploaded as the administrator, activated, and the container restarts
#      itself into it: exit 75, the loop, the launcher, the new version
#   3. rolled back to the image's own jar, the same way
#   4. activated again, then one byte of it changed in the database with its
#      sha256 rewritten to match - and the next start refuses it, runs the
#      image's jar, and marks the release FAILED
#
# Needs: say, ok, die from the caller; docker; curl.

# `pwd -W` is Git Bash's Windows spelling of the path, which is what Docker
# Desktop mounts; everywhere else it fails and plain `pwd` is the answer.
SELF_UPDATE_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
SELF_UPDATE_JDK="eclipse-temurin:25-jdk"

# self_update_prepare IMAGE WORK
#   Leaves WORK/test.jar (signed), WORK/trust/e2e.pem, the JDBC drivers under
#   WORK/BOOT-INF/lib, and sets SU_IMAGE_VERSION and SU_TEST_VERSION.
self_update_prepare() {
  local image="$1" work="$2" box
  mkdir -p "$work/trust"
  box="$(docker create "$image")"
  docker cp "$box:/app/app.jar" "$work/app.jar" >/dev/null
  docker rm "$box" >/dev/null

  SU_IMAGE_VERSION="$(docker run --rm --user "$(id -u):$(id -g)" -v "$work:/work" -v "$SELF_UPDATE_TOOLS:/tools:ro" -w /work \
    "$SELF_UPDATE_JDK" java /tools/Repack.java app.jar unsigned.jar 0 | tail -n 1)"
  [ -n "$SU_IMAGE_VERSION" ] || die "The image's jar carries no Implementation-Version"
  SU_TEST_VERSION="${SU_IMAGE_VERSION%%-*}.1"
  # #593: one to pin, and two that cannot start - one pinned, one chosen in the database.
  SU_PIN_VERSION="${SU_IMAGE_VERSION%%-*}.2"
  SU_BROKEN_PIN_VERSION="${SU_IMAGE_VERSION%%-*}.3"
  SU_BROKEN_VERSION="${SU_IMAGE_VERSION%%-*}.4"

  docker run --rm --user "$(id -u):$(id -g)" -v "$work:/work" -v "$SELF_UPDATE_TOOLS:/tools:ro" -w /work \
    -e HOME=/tmp "$SELF_UPDATE_JDK" sh -euc "
      keytool -genkeypair -keyalg Ed25519 -alias e2e -dname 'CN=Orknux self-update test' -validity 2 \
        -keystore e2e.p12 -storetype PKCS12 -storepass e2e-test -keypass e2e-test 2>/dev/null
      keytool -exportcert -rfc -alias e2e -keystore e2e.p12 -storepass e2e-test -file trust/e2e.pem 2>/dev/null
      sign() {
        java /tools/Repack.java app.jar unsigned.jar \"\$2\" \${3:-} >/dev/null
        jarsigner -keystore e2e.p12 -storepass e2e-test -signedjar \"\$1\" unsigned.jar e2e >/dev/null
        jarsigner -verify \"\$1\" >/dev/null
        rm -f unsigned.jar
      }
      sign test.jar '$SU_TEST_VERSION'
      sign pin.jar '$SU_PIN_VERSION'
      sign broken-pin.jar '$SU_BROKEN_PIN_VERSION' broken
      sign broken.jar '$SU_BROKEN_VERSION' broken
      jar tf app.jar | grep -E '^BOOT-INF/lib/(sqlite-jdbc|slf4j-api|postgresql)-[0-9]' | xargs jar xf app.jar
    " || die "Could not build and sign the test releases"
  # Readable by the image's own user too, which is who changes the database
  # later: mktemp makes a directory only its owner may enter.
  chmod -R a+rX "$work"
  ok "Built $SU_TEST_VERSION, $SU_PIN_VERSION and the unbootable $SU_BROKEN_PIN_VERSION and $SU_BROKEN_VERSION from the image's $SU_IMAGE_VERSION, signed with a key made for this run"
}

# The environment a container needs to trust the test key. TEST ONLY.
self_update_trust_args() {
  printf '%s\n' -e ORKNUX_RELEASE_TEST=true -e ORKNUX_RELEASE_TRUST_EXTRA=/trust/e2e.pem -v "$1/trust:/trust:ro"
}

_su_signin() {
  curl -fsS -m 15 -X POST "$SU_BASE/api/session" -H 'content-type: application/json' \
    -d "{\"username\":\"$SU_USER\",\"password\":\"$SU_PASSWORD\"}" -c "$SU_COOKIES" >/dev/null
}

_su_gql() {
  curl -fsS -m 120 -X POST "$SU_BASE/graphql" -H 'content-type: application/json' -b "$SU_COOKIES" -d "$1"
}

_su_running() {
  _su_signin 2>/dev/null || return 1
  _su_gql '{"query":"{ serverUpdates { runningVersion } }"}' 2>/dev/null \
    | sed -n 's/.*"runningVersion":"\([^"]*\)".*/\1/p'
}

# Waits until the container answers as VERSION, on its own: the container must
# stay up throughout, because the restart is the loop's and not Docker's.
_su_wait_for() {
  local expected="$1" waited=0 now
  while [ "$waited" -lt 300 ]; do
    now="$(_su_running || true)"
    [ "$now" = "$expected" ] && return 0
    if [ "$(docker inspect -f '{{.State.Running}}' "$SU_APP" 2>/dev/null)" != "true" ]; then
      docker logs "$SU_APP" 2>&1 | tail -60
      die "The container exited instead of restarting itself (exit $(docker inspect -f '{{.State.ExitCode}}' "$SU_APP"))"
    fi
    sleep 2
    waited=$(( waited + 2 ))
  done
  docker logs "$SU_APP" 2>&1 | tail -60
  die "It never came back as $expected (last answered: ${now:-nothing})"
}

# self_update_run APP BASE USER PASSWORD WORK TAMPER
#   TAMPER is a command the caller provides that changes the chosen release in
#   its database while the container is stopped.
self_update_run() {
  SU_APP="$1" SU_BASE="$2" SU_USER="$3" SU_PASSWORD="$4"
  local work="$5" tamper="$6" answer id started
  SU_COOKIES="$work/cookies.txt"
  started="$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")"

  say "Updating the running container to a release it was handed"
  _su_signin || die "The administrator could not sign in"
  [ "$(_su_running)" = "$SU_IMAGE_VERSION" ] || die "It does not report the image's own $SU_IMAGE_VERSION before the update"

  answer="$(curl -sS -m 600 -X POST "$SU_BASE/api/server-releases" -b "$SU_COOKIES" \
    -H 'content-type: application/octet-stream' --data-binary "@$work/test.jar")" || die "The upload failed outright"
  id="$(printf '%s' "$answer" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')"
  [ -n "$id" ] || die "The upload was refused: $answer"
  ok "Uploaded and verified as release $id"

  answer="$(_su_gql "{\"query\":\"mutation { activateServerRelease(id: $id) { restarting } }\"}")"
  case "$answer" in
    *'"restarting":true'*) ok "Activated; the server says it is restarting" ;;
    *) die "Activating did not restart: $answer" ;;
  esac
  _su_wait_for "$SU_TEST_VERSION"
  ok "It came back as $SU_TEST_VERSION"

  # The same container, restarted by its own loop rather than by Docker.
  [ "$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")" = "$started" ] || die "Docker restarted the container; the loop should have"
  case "$(docker logs "$SU_APP" 2>&1)" in
    *"asked to be started again"*) ok "The server exited 75 and the start loop chose again" ;;
    *) die "The log does not show the start loop restarting the server" ;;
  esac

  say "Rolling back to the image's own jar"
  answer="$(_su_gql '{"query":"mutation { activateImageRelease { restarting } }"}')"
  case "$answer" in
    *'"restarting":true'*) ;;
    *) die "Rolling back did not restart: $answer" ;;
  esac
  _su_wait_for "$SU_IMAGE_VERSION"
  ok "It came back as the image's $SU_IMAGE_VERSION"

  say "Changing the stored jar in the database, hash and all"
  _su_signin
  answer="$(_su_gql "{\"query\":\"mutation { activateServerRelease(id: $id) { restarting } }\"}")"
  case "$answer" in *'"restarting":true'*) ;; *) die "Re-applying did not restart: $answer" ;; esac
  _su_wait_for "$SU_TEST_VERSION"
  ok "Back on $SU_TEST_VERSION"

  docker stop -t 30 "$SU_APP" >/dev/null
  "$tamper" || die "Could not change the stored jar"
  ok "One byte changed and its sha256 rewritten to match"
  docker start "$SU_APP" >/dev/null
  _su_wait_for "$SU_IMAGE_VERSION"
  ok "The next start ran the image's own jar"

  answer="$(_su_gql '{"query":"{ serverUpdates { stored { id state failure } } }"}')"
  case "$answer" in
    *"\"id\":\"$id\",\"state\":\"FAILED\""*) ok "Release $id is marked FAILED: $(printf '%s' "$answer" | sed -n 's/.*"failure":"\([^"]*\)".*/\1/p')" ;;
    *) die "The tampered release is not marked FAILED: $answer" ;;
  esac
  case "$(docker logs "$SU_APP" 2>&1)" in
    *"orknux launcher: ERROR"*) ok "The launcher said so, loudly, in the log" ;;
    *) die "The launcher refused it without a word in the log" ;;
  esac
}

_su_upload() {
  local answer id
  answer="$(curl -sS -m 600 -X POST "$SU_BASE/api/server-releases" -b "$SU_COOKIES" \
    -H 'content-type: application/octet-stream' --data-binary "@$1")" || die "The upload of $1 failed outright"
  id="$(printf '%s' "$answer" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')"
  [ -n "$id" ] || die "The upload of $1 was refused: $answer"
  printf '%s' "$id"
}

# The state and failure of stored release ID, as "STATE|failure".
_su_release() {
  _su_gql '{"query":"{ serverUpdates { stored { id state failure } } }"}' \
    | tr '{' '\n' | grep "\"id\":\"$1\"" | sed -n 's/.*"state":"\([^"]*\)","failure":\(null\|"\([^"]*\)"\).*/\1|\3/p'
}

# self_update_fallbacks WORK RECREATE
#   #593: ORKNUX_RELEASE_PIN, and the way back from a jar that does not start.
#   RECREATE is a command the caller provides that removes the container and
#   starts it again on the same database, with any extra `docker run`
#   arguments it is handed - an environment variable cannot be changed on a
#   container that exists. Runs after self_update_run, on the image's own jar.
#
#   1. pinned to a kept release: it runs, and the page cannot choose over it
#   2. pinned to the image's own version: the image runs
#   3. pinned to a release that cannot start: the loop restarts it, the
#      launcher gives up on it, and the image runs - the container never exits
#   4. unpinned, a release chosen on Admin -> Updates that cannot start: the
#      same, from the database's side
self_update_fallbacks() {
  local work="$1" recreate="$2" pin_id broken_pin_id broken_id answer started state

  say "Pinning the version with ORKNUX_RELEASE_PIN (#593)"
  _su_signin || die "The administrator could not sign in"
  pin_id="$(_su_upload "$work/pin.jar")"
  broken_pin_id="$(_su_upload "$work/broken-pin.jar")"
  broken_id="$(_su_upload "$work/broken.jar")"
  ok "Stored $SU_PIN_VERSION, and $SU_BROKEN_PIN_VERSION and $SU_BROKEN_VERSION, which cannot start"

  "$recreate" -e "ORKNUX_RELEASE_PIN=$SU_PIN_VERSION"
  _su_wait_for "$SU_PIN_VERSION"
  ok "Pinned to $SU_PIN_VERSION, it runs $SU_PIN_VERSION"
  answer="$(_su_gql '{"query":"{ serverUpdates { pin pinRefusal } }"}')"
  case "$answer" in
    *"\"pin\":\"$SU_PIN_VERSION\",\"pinRefusal\":null"*) ok "Admin -> Updates says what is pinned" ;;
    *) die "Admin -> Updates does not report the pin: $answer" ;;
  esac
  answer="$(_su_gql '{"query":"mutation { activateImageRelease { restarting } }"}')"
  case "$answer" in
    *ServerReleasePinned*) ok "And refuses to choose over it" ;;
    *) die "Admin -> Updates chose over the pin: $answer" ;;
  esac

  "$recreate" -e "ORKNUX_RELEASE_PIN=$SU_IMAGE_VERSION"
  _su_wait_for "$SU_IMAGE_VERSION"
  ok "Pinned to the image's own $SU_IMAGE_VERSION, it runs the image"

  say "Pinning a release that cannot start"
  "$recreate" -e "ORKNUX_RELEASE_PIN=$SU_BROKEN_PIN_VERSION"
  started="$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")"
  _su_wait_for "$SU_IMAGE_VERSION"
  [ "$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")" = "$started" ] || die "The container restarted; the loop should have kept it up"
  ok "It gave up on $SU_BROKEN_PIN_VERSION and runs the image's own $SU_IMAGE_VERSION, in the same container"
  case "$(docker logs "$SU_APP" 2>&1)" in
    *"exited with"*"choosing a jar"*) ok "The start loop chose again each time it failed" ;;
    *) die "The log does not show the start loop choosing again after a failed start" ;;
  esac
  state="$(_su_release "$broken_pin_id")"
  case "$state" in
    FAILED*"did not start"*) ok "Release $SU_BROKEN_PIN_VERSION is marked failed: ${state#*|}" ;;
    *) die "The unbootable pinned release is not marked failed: $state" ;;
  esac
  answer="$(_su_gql '{"query":"{ serverUpdates { pin pinRefusal } }"}')"
  case "$answer" in
    *'"pinRefusal":"release '"$SU_BROKEN_PIN_VERSION"' is marked failed'*) ok "Admin -> Updates says why the pin is not running" ;;
    *) die "Admin -> Updates does not say why the pin is not running: $answer" ;;
  esac

  say "Choosing, on Admin -> Updates, a release that cannot start"
  "$recreate"
  _su_wait_for "$SU_IMAGE_VERSION"
  started="$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")"
  _su_signin
  answer="$(_su_gql "{\"query\":\"mutation { activateServerRelease(id: $broken_id) { restarting } }\"}")"
  case "$answer" in *'"restarting":true'*) ;; *) die "Activating $SU_BROKEN_VERSION did not restart: $answer" ;; esac
  # It answers as the image before it leaves; wait for the release to be given up on, not for the first answer.
  for _ in $(seq 1 150); do
    case "$(_su_release "$broken_id" 2>/dev/null || true)" in FAILED*) break ;; esac
    sleep 2
  done
  _su_wait_for "$SU_IMAGE_VERSION"
  [ "$(docker inspect -f '{{.State.StartedAt}}' "$SU_APP")" = "$started" ] || die "The container restarted; the loop should have kept it up"
  state="$(_su_release "$broken_id")"
  case "$state" in
    FAILED*"did not start"*) ok "Release $SU_BROKEN_VERSION is marked failed, and the image's own $SU_IMAGE_VERSION runs: ${state#*|}" ;;
    *) die "The unbootable chosen release is not marked failed: $state" ;;
  esac
  : "$pin_id"
}
