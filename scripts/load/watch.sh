#!/usr/bin/env bash
#
# The other half of scripts/load/teams.py (#587): what the container did while
# the teams worked. Every ten seconds, one line of its memory against the limit,
# the JVM's own resident size, and how many times it has restarted - and why:
#
#   OOMKilled   the kernel killed it at the container's memory limit (137)
#   exit N      the JVM left on its own (an OutOfMemoryError is 1 or 3)
#   liveness    this script stood in for Kubernetes and killed it, because
#               /api/auth/method missed three probes in a row - the probe in
#               deploy/kubernetes/orknux.yaml, period 20s, timeout 5s
#
# Usage:  scripts/load/watch.sh CONTAINER PORT [SECONDS]
# Run the container with --restart unless-stopped so a crash comes back the way
# a pod does, and with --cpus 1 --memory 2g to be the production pod.

set -uo pipefail

APP="$1"
PORT="$2"
SECONDS_LEFT="${3:-3600}"
PERIOD=20
TIMEOUT=5
THRESHOLD=3

misses=0
last_probe=0
liveness=0
seen_restarts="$(docker inspect -f '{{.RestartCount}}' "$APP")"
ended=$(( $(date +%s) + SECONDS_LEFT ))

# A restart policy brings it back before an inspect can see why it went, so the
# daemon's own events say it: an oom, then a die with its exit code.
docker events --filter "container=$APP" --filter event=oom --filter event=die \
  --format '# {{.Time}} {{.Action}} exitCode={{index .Actor.Attributes "exitCode"}}' >&2 &
events=$!
trap 'kill $events 2>/dev/null' EXIT

echo "time,mem_used,mem_limit,java_rss_kb,restarts,oom_killed,last_exit,liveness_kills,probe_ms"
while [ "$(date +%s)" -lt "$ended" ]; do
  now=$(date +%s)
  probe_ms=""
  if [ $(( now - last_probe )) -ge "$PERIOD" ]; then
    last_probe=$now
    began=$(date +%s%N)
    if curl -fsS -m "$TIMEOUT" "http://localhost:$PORT/api/auth/method" >/dev/null 2>&1; then
      misses=0
    else
      misses=$(( misses + 1 ))
    fi
    probe_ms=$(( ($(date +%s%N) - began) / 1000000 ))
    if [ "$misses" -ge "$THRESHOLD" ] && [ "$(docker inspect -f '{{.State.Running}}' "$APP")" = "true" ]; then
      liveness=$(( liveness + 1 ))
      echo "# $(date +%T) liveness probe failed $THRESHOLD times; killing $APP as the kubelet would" >&2
      docker restart -t 30 "$APP" >/dev/null
      misses=0
    fi
  fi
  stats="$(docker stats --no-stream --format '{{.MemUsage}}' "$APP" 2>/dev/null | tr -d ' ')"
  rss="$(docker exec "$APP" sh -c "ps -eo rss,args | grep -v grep | grep 'java .*-jar' | awk '{print \$1}' | head -1" 2>/dev/null)"
  restarts="$(docker inspect -f '{{.RestartCount}}' "$APP")"
  oom="$(docker inspect -f '{{.State.OOMKilled}}' "$APP")"
  code="$(docker inspect -f '{{.State.ExitCode}}' "$APP")"
  if [ "$restarts" != "$seen_restarts" ]; then
    echo "# $(date +%T) $APP restarted (count $restarts, OOMKilled=$oom, exit $code)" >&2
    seen_restarts="$restarts"
  fi
  echo "$(date +%T),${stats%%/*},${stats##*/},${rss:-},$restarts,$oom,$code,$liveness,$probe_ms"
  sleep 10
done
