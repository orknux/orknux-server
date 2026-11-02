#!/usr/bin/env bash
#
# Retakes every picture in the manual, against an installation made for it.
#
# The Linux half of scripts/screenshots.ps1, which is the Windows one. One
# command, from a checkout of orknux-server:
#
#   scripts/screenshots.sh
#   scripts/screenshots.sh --endpoint http://localhost:8299 --model writer --provider-type LLAMA_CPP
#   scripts/screenshots.sh --only chat,chat-voice --skip-build
#
# The pictures land in orknux-ui/public/screens in this working copy. Commit
# them there and move the submodule pin, as any other change to the interface.
#
# Read screenshots.ps1's header first. Everything it says - why there is an
# installation at all, what is in screens-compose.yaml, and the things that
# cost an afternoon to learn - is true here too. The two scripts take the same
# options with the same defaults and do the same steps in the same order, and
# a change to one is a change to both: a step added here and not there is a
# manual that looks different depending on which machine photographed it.
#
# What is different on Linux, and nothing else is:
#
# - The build is ./mvnw, and Java is $JAVA_HOME/bin/java where JAVA_HOME is set,
#   or whatever `java` is on PATH. A shell that has not sourced sdkman has
#   neither, which is said before the build rather than by Maven afterwards.
# - Rootless Docker has no route from a container to the host's loopback, so
#   the interface container cannot reach the jar as host.docker.internal; see
#   screens-compose.rootless.yaml, which this script adds when `docker info`
#   says the daemon is rootless. One consequence: the interface's port is then
#   bound inside rootlesskit's namespace, so it is waited for from inside its
#   container rather than with curl from here.
# - The temporary installation is under $TMPDIR, or /tmp.
# - A port is free when nothing answers a connection to it, which is a weaker
#   test than binding, but the one that catches what has actually bitten: a
#   server of this product already on the port.
#
# Options, each the same as the PowerShell parameter of that name:
#
#   --endpoint URL          where the chat model answers, as the server on this
#                           machine reaches it (default http://localhost:11434)
#   --model ID              the model id; it must have tool support (gemma4:e4b)
#   --provider-type TYPE    OLLAMA (default), or LLAMA_CPP for a llama.cpp server
#   --only a,b              picture names, passed to ORKNUX_ONLY
#   --skip-build            use the jar already in app/target; never for a release
#   --keep-data             leave the temporary installation on disk, and say where
#   --hold                  once the capture has run, keep the installation up until
#                           this is stopped, to retake a picture with `docker compose
#                           -p orknux-screens exec ui` rather than a whole new seed;
#                           this one has no PowerShell twin yet
#   --picture-endpoint URL  a stable-diffusion.cpp server; empty keeps the stand-in
#   --lane N                run N of several at once (0-50): moves every port not
#                           named by 10N and runs as compose project orknux-screens-N
#   --server-port N         the jar (8181)       --ui-port N        vite (5199)
#   --draw-port N           the stand-in model (8299)
#   --ldap-port N           the directory (3899) --temporal-port N  Temporal (7299)
set -euo pipefail

endpoint='http://localhost:11434'
model='gemma4:e4b'
provider_type='OLLAMA'
only=''
skip_build=false
keep_data=false
hold=false
picture_endpoint=''
lane=0
server_port=''
ui_port=''
draw_port=''
ldap_port=''
temporal_port=''

usage() {
  sed -n '/^# Options,/,/^set -euo/p' "$0" | sed '$d' | cut -c3-
}

while [ $# -gt 0 ]; do
  case "$1" in
    --endpoint) endpoint=$2; shift 2 ;;
    --model) model=$2; shift 2 ;;
    --provider-type) provider_type=$2; shift 2 ;;
    --only) only=$2; shift 2 ;;
    --skip-build) skip_build=true; shift ;;
    --keep-data) keep_data=true; shift ;;
    --hold) hold=true; shift ;;
    --picture-endpoint) picture_endpoint=$2; shift 2 ;;
    --lane) lane=$2; shift 2 ;;
    --server-port) server_port=$2; shift 2 ;;
    --ui-port) ui_port=$2; shift 2 ;;
    --draw-port) draw_port=$2; shift 2 ;;
    --ldap-port) ldap_port=$2; shift 2 ;;
    --temporal-port) temporal_port=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

started=$(date +%s)
root=$(cd "$(dirname "$0")/.." && pwd)
here=$root/scripts
compose=$here/screens-compose.yaml

# A lane moves every port nobody named, and runs under a project of its own.
# Lane 0 is the single run as it always was, project name and all.
if [ "$lane" -lt 0 ] || [ "$lane" -gt 50 ]; then
  echo "--lane is 0 to 50." >&2
  exit 2
fi
offset=$((10 * lane))
: "${server_port:=$((8181 + offset))}"
: "${ui_port:=$((5199 + offset))}"
: "${draw_port:=$((8299 + offset))}"
: "${ldap_port:=$((3899 + offset))}"
: "${temporal_port:=$((7299 + offset))}"
project=orknux-screens
[ "$lane" -eq 0 ] || project="orknux-screens-$lane"
compose_args=(compose -p "$project" -f "$compose")

# Rootless Docker: the interface goes on the host's network. The file says why.
rootless=false
if docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q rootless; then
  rootless=true
  compose_args+=(-f "$here/screens-compose.rootless.yaml")
fi

# The one port each, decided here and handed to compose, which defaults to the
# same numbers. Nothing may invent another.
export ORKNUX_SCREENS_SERVER_PORT=$server_port
export ORKNUX_SCREENS_UI_PORT=$ui_port
export ORKNUX_SCREENS_DRAW_PORT=$draw_port
export ORKNUX_SCREENS_LDAP_PORT=$ldap_port
export ORKNUX_SCREENS_TEMPORAL_PORT=$temporal_port
ui_origin="http://localhost:$ui_port"

step() {
  local elapsed=$(( $(date +%s) - started ))
  if [ -t 1 ]; then printf '\033[36m[%02d:%02d] %s\033[0m\n' $((elapsed / 60)) $((elapsed % 60)) "$1"
  else printf '[%02d:%02d] %s\n' $((elapsed / 60)) $((elapsed % 60)) "$1"; fi
}

# Up, for anything that speaks HTTP. A status code is a status code: 401 from
# the sign-in endpoint is the server answering, which is all this is asking.
# `000` is curl's way of saying there was nothing there to ask.
wait_for_http() {
  local url=$1 seconds=$2 what=$3 code
  local deadline=$((SECONDS + seconds))
  while [ "$SECONDS" -lt "$deadline" ]; do
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$url" || true)
    [ "$code" != '000' ] && return 0
    sleep 2
  done
  echo "$what never answered at $url." >&2
  return 1
}

# The interface, asked from inside its container, where its port is reachable
# whichever network it is on.
wait_for_ui() {
  local seconds=$1
  local deadline=$((SECONDS + seconds))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if docker "${compose_args[@]}" exec -T ui node -e \
      "fetch('$ui_origin/').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "The interface never answered at $ui_origin." >&2
  return 1
}

assert_port_free() {
  local port=$1 what=$2 option=$3
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "Port $port is taken, and $what needs it. Pass $option with one that is not." >&2
    exit 1
  fi
}

# --------------------------------------------------------------- preconditions

# The two halves are released together under one number and the footer prints
# the interface's on every screen, so a set taken while they disagree is a set
# that has to be taken again. Checked before the build, which is the expensive
# part. The project's own <version>, not its parent's.
version=$(awk '/<parent>/ { p = 1 } /<\/parent>/ { p = 0; next } !p && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' "$root/pom.xml")
ui_version=$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$root/orknux-ui/package.json" | head -1)
if [ "$version" != "$ui_version" ]; then
  echo "pom.xml is $version and orknux-ui/package.json is $ui_version. Bump the interface to the release being photographed first: the footer carries it on every screen." >&2
  exit 1
fi

java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
if ! command -v "$java_bin" >/dev/null 2>&1; then
  echo "No java: set JAVA_HOME, or put one on PATH. The build and the server both need it." >&2
  exit 1
fi
for tool in docker curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "No $tool on PATH." >&2; exit 1; }
done

assert_port_free "$server_port" 'the server' '--server-port'
assert_port_free "$ui_port" 'the interface' '--ui-port'
assert_port_free "$draw_port" 'the drawing model' '--draw-port'
assert_port_free "$ldap_port" 'the directory' '--ldap-port'
assert_port_free "$temporal_port" 'Temporal' '--temporal-port'

step "Photographing $version, lane $lane ($project): server $server_port, interface $ui_port$($rootless && echo ', rootless Docker')."

# ---------------------------------------------------- the installation, part one
#
# Started before the build rather than after it. Temporal applies its schema on
# every start and the interface container installs its dependencies and a
# browser; that is a few minutes which may as well be the same few minutes
# Maven is using. Waited for further down.
step 'Starting the directory, Temporal, the drawing model and the interface.'
# Temporal's own database starts empty every run: kept from the last run it
# still holds the task numbers a fresh SQLite file hands out again, and the
# seed's first task is refused as already started. Left from a run that stopped
# half way, the containers go first, or the volume is still in use and stays.
docker "${compose_args[@]}" down --remove-orphans >/dev/null 2>&1 || true
docker volume rm -f "${project}_postgres-data" >/dev/null 2>&1 || true
docker "${compose_args[@]}" up -d

# ------------------------------------------------------------------------ the jar

if $skip_build; then
  step 'Using the jar already in app/target, as asked.'
else
  step 'Building the jar.'
  (cd "$root" && ./mvnw -q -DskipTests -pl app -am package)
fi
jar=$root/app/target/orknux-app-$version.jar
if [ ! -f "$jar" ]; then
  echo "No $jar. Run without --skip-build." >&2
  exit 1
fi

# ---------------------------------------------------- the installation, part two

# The lane and the process in the name as well as the time: two lanes started
# in the same second must not share a database.
data=$(mktemp -d "${TMPDIR:-/tmp}/orknux-screens-$lane-$$-$(date +%Y%m%d-%H%M%S).XXXX")

# A key of its own, generated and thrown away with the database it protects.
# The server reads it on first use, so without one it starts, reports itself
# healthy, and fails on the first credential the seed stores.
export ORKNUX_PORT=$server_port
export ORKNUX_DB_URL="jdbc:sqlite:$data/orknux.db"
ORKNUX_SECRET_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
export ORKNUX_SECRET_KEY
export ORKNUX_LDAP_URLS="ldap://localhost:$ldap_port"
export ORKNUX_TEMPORAL_TARGET="localhost:$temporal_port"
# The origin the browser will actually be on. See the note at the top of screenshots.ps1.
export ORKNUX_ALLOWED_ORIGINS=$ui_origin
export ORKNUX_BASE_URL=$ui_origin
# Left alone deliberately: orknux.attachments.location stays `data/attachments`,
# relative to the working directory below, because that is the string the
# Doctor page prints and the capture's redaction is written against it.

server_pid=''
cleanup() {
  local status=$?
  if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    for _ in $(seq 30); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$server_pid" 2>/dev/null || true
  fi
  # Without -v: the browser and the dependency tree are worth keeping between
  # runs, and none of them is anybody's data. Temporal's database is removed at
  # the next start instead, above, so it never meets an installation it did not
  # see begin.
  docker "${compose_args[@]}" down --remove-orphans >/dev/null 2>&1 || true
  if $keep_data; then
    step "The installation is still at $data."
  else
    rm -rf "$data"
  fi
  exit "$status"
}
trap cleanup EXIT

step "Starting the server on $server_port, storing in $data/orknux.db."
(cd "$data" && exec "$java_bin" -jar "$jar" >"$data/server.log" 2>"$data/server.err") &
server_pid=$!

# Everything the server needs has to be up before it is: it reads the
# directory at the first sign-in and Temporal at the first run.
step 'Waiting for the containers.'
docker "${compose_args[@]}" up -d --wait --wait-timeout 900
wait_for_ui 600
wait_for_http "http://localhost:$server_port/api/session" 300 'The server'

step "Seeding the demonstration workspace ($model at $endpoint)."
docker "${compose_args[@]}" exec -T \
  -e "ORKNUX_DEMO_ENDPOINT=$endpoint" -e "ORKNUX_DEMO_MODEL=$model" \
  -e "ORKNUX_DEMO_IMAGE_ENDPOINT=http://localhost:$draw_port" \
  -e "ORKNUX_DEMO_PICTURE_ENDPOINT=$picture_endpoint" -e "ORKNUX_DEMO_TYPE=$provider_type" \
  -e "ORKNUX_DEMO_MANUAL=1" \
  ui node scripts/seed-demo.mjs

step 'Taking the pictures.'
capture=0
docker "${compose_args[@]}" exec -T -e "ORKNUX_ONLY=$only" ui node scripts/screenshots.mjs || capture=$?
if $hold; then
  step "Held: the server is on $server_port, the interface on $ui_port. Stop this script to take it down."
  sleep infinity &
  wait $!
fi
[ "$capture" -eq 0 ] || exit "$capture"

step 'Done. They are in orknux-ui/public/screens; git status there says which moved.'
