#!/usr/bin/env bash
# Two servers on one Postgres: one sweeps, one listens to Slack, and when it
# dies the other takes both over. Issue #597.
#
#   scripts/cluster-smoke/run.sh [path/to/orknux-app.jar] [base image]
#
# The suite proves the lease with two holders in one JVM (ClusterLeaderTest,
# on both databases). This is the same claim made of two real processes in
# two containers, with Temporal on, as a deployment would run them:
#
#   1. exactly one server says it holds the cluster lease
#   2. the model provider check runs on that server and not on the other -
#      read off each container's own log, so there is no guessing who swept
#   3. the Slack connection is tried by the leader and reported ELSEWHERE by
#      the follower, which never opens a socket
#   4. the leader is killed with SIGKILL, so it cannot let go; the survivor
#      takes the lease within one lease, starts sweeping, and owns Slack
#
# A schedule firing once is db-scheduler's own guarantee and is not repeated
# here; TriggerSchedulerIntegrationTest starts that scheduler for real.
#
# Needs docker and curl. Leaves nothing behind: the project is taken down with
# its volumes at the end, pass or fail.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="${1:-$(ls "$ROOT"/app/target/orknux-app-*.jar | head -n 1)}"
BASE="${2:-orknux/orknux-server:0.9.9.10}"
LEASE=10
COMPOSE=(docker compose -f "$HERE/compose.yaml")
WORK="$(mktemp -d)"
failed=0

say() { printf '\n== %s\n' "$*"; }
ok() { printf 'PASS: %s\n' "$*"; }
bad() { printf 'FAIL: %s\n' "$*"; failed=1; }

cleanup() {
  "${COMPOSE[@]}" logs --no-color server-a >"$WORK/a.log" 2>&1 || true
  "${COMPOSE[@]}" logs --no-color server-b >"$WORK/b.log" 2>&1 || true
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  echo "Logs kept in $WORK"
}
trap cleanup EXIT

say "Building orknux-cluster-smoke:local from $BASE and $(basename "$JAR")"
cp "$JAR" "$WORK/app.jar"
printf 'FROM %s\nCOPY app.jar /app/app.jar\n' "$BASE" >"$WORK/Dockerfile"
docker build -q -t orknux-cluster-smoke:local "$WORK" >/dev/null

say "Starting Postgres, Temporal and two servers"
"${COMPOSE[@]}" up -d >/dev/null
for port in 18191 18192; do
  for _ in $(seq 1 90); do
    curl -fs "http://localhost:$port/api/auth/method" >/dev/null 2>&1 && break
    sleep 2
  done
  curl -fs "http://localhost:$port/api/auth/method" >/dev/null || { bad "server on $port did not come up"; exit 1; }
done
ok "both servers answer"

signin() { curl -fs -c "$WORK/$1.jar" -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password-for-alice"}' "http://localhost:$1/api/session" >/dev/null; }
gql() { # port, query
  local body
  body="$(printf '%s' "$2" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ')"
  curl -fs -b "$WORK/$1.jar" -H 'Content-Type: application/json' -d "{\"query\":\"$body\"}" "http://localhost:$1/graphql"
}
logs() { "${COMPOSE[@]}" logs --no-color "$1" 2>/dev/null; }
leaderOf() {
  # The start-up line says "held as" on the server that took it and "held by
  # another server as" on the one that did not.
  for s in server-a server-b; do
    logs "$s" | grep -q "Cluster lease orknux-leader held as" && echo "$s"
  done
  return 0
}
portOf() { [ "$1" = server-a ] && echo 18191 || echo 18192; }
checks() { logs "$1" | grep -c "Checked 1 model providers" || true; }

signin 18191
signin 18192

say "1. One server holds the lease"
leaders="$(leaderOf)"
if [ "$(echo "$leaders" | grep -c .)" = 1 ]; then ok "the lease is held by $leaders alone"; else bad "lease holders: [$leaders]"; fi
LEADER="$(echo "$leaders" | head -n 1)"
FOLLOWER=$([ "$LEADER" = server-a ] && echo server-b || echo server-a)
gql "$(portOf "$FOLLOWER")" '{ doctor { name detail } }' | grep -o '"name":"Replicas","detail":"[^"]*"' || true

say "Making a workspace, a model provider and a Slack connection"
ws="$(gql 18191 'mutation { createWorkspace(input: { name: "cluster-smoke" }) { id } }' | grep -o '"id":"[0-9]*"' | grep -o '[0-9]*')"
gql 18191 "mutation { createModelProvider(input: { workspaceId: \"$ws\", name: \"Nowhere\", endpoint: \"https://provider.invalid/v1\", type: OPENAI, secret: \"sk-not-a-key\" }) { id } }" >/dev/null
conn="$(gql 18191 "mutation { createWorkspaceConnection(input: { workspaceId: \"$ws\", name: \"Slack\", type: SLACK, url: \"https://slack.com/api\", secret: \"xoxb-not-a-token\", appToken: \"xapp-not-a-token\" }) { id } }" | grep -o '"id":"[0-9]*"' | grep -o '[0-9]*')"
[ -n "$ws" ] && [ -n "$conn" ] && ok "workspace $ws, connection $conn" || bad "could not make the fixtures"

sleep 25

say "2. The model check runs on the leader only"
la="$(checks "$LEADER")"; fb="$(checks "$FOLLOWER")"
if [ "$la" -gt 0 ] && [ "$fb" = 0 ]; then ok "$LEADER swept $la times, $FOLLOWER none"; else bad "$LEADER swept $la, $FOLLOWER $fb"; fi

say "3. Slack is tried by the leader and left to it by the follower"
socket() { gql "$(portOf "$1")" "{ workspaceConnection(id: \"$conn\") { slackSocket { status } } }" | grep -o '"status":"[A-Z_]*"' | cut -d'"' -f4; }
ls="$(socket "$LEADER")"; fs="$(socket "$FOLLOWER")"
if [ "$ls" != ELSEWHERE ] && [ -n "$ls" ] && [ "$fs" = ELSEWHERE ]; then ok "$LEADER says $ls, $FOLLOWER says $fs"; else bad "$LEADER says '$ls', $FOLLOWER says '$fs'"; fi

say "4. The leader dies without letting go"
before="$(checks "$FOLLOWER")"
"${COMPOSE[@]}" kill -s SIGKILL "$LEADER" >/dev/null
took=""
for i in $(seq 1 $((LEASE * 3))); do
  if logs "$FOLLOWER" | grep -q "now leads"; then took="$i"; break; fi
  sleep 1
done
if [ -n "$took" ]; then ok "$FOLLOWER took the lease ${took}s after $LEADER was killed (lease ${LEASE}s)"; else bad "$FOLLOWER never took the lease"; fi
sleep 12
after="$(checks "$FOLLOWER")"
if [ "$after" -gt "$before" ]; then ok "$FOLLOWER sweeps now ($before -> $after)"; else bad "$FOLLOWER still does not sweep ($before -> $after)"; fi
ns="$(socket "$FOLLOWER")"
if [ "$ns" != ELSEWHERE ] && [ -n "$ns" ]; then ok "$FOLLOWER owns Slack now ($ns)"; else bad "$FOLLOWER says '$ns' about Slack"; fi

echo
if [ "$failed" = 0 ]; then echo "ALL PASS"; else echo "SOME FAILED"; fi
exit "$failed"
