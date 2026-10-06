#!/bin/sh
#
# How both images start the server: choose a jar, run it, and do it again when
# it asks. Issue #584.
#
# An administrator can update this server in place - a release from orknux.ai or
# a jar they uploaded, kept in the database. The launcher (ReleaseLauncher in the
# image's own jar) decides which jar this start runs: the release the database
# chose, written out and checked against the release certificate the image
# carries, or the image's own jar whenever anything about that is not right. It
# prints the path and this runs it.
#
# Exit code 75 from the server means "a different release was chosen; start me
# again", so it loops. Anything else from the image's own jar is the server's
# own answer and this exits with it, which is what Docker and Kubernetes restart
# on. A stored release that exits is chosen again instead, with the launcher
# told how it ended, until it is given up on and the image's jar runs (#593).
#
# This shell is PID 1 and the JVM is its child, so a signal has to be handed on
# by hand: SIGTERM from `docker stop` or a pod deletion goes to the JVM, which
# shuts down gracefully, and its exit status is this container's. A shell that
# did not trap would leave the JVM to be killed at the end of the grace period.
#
# ORKNUX_SELF_UPDATE=false skips the launcher and execs the image's jar, exactly
# as these images did before the feature existed.

IMAGE_JAR="${ORKNUX_IMAGE_JAR:-/app/app.jar}"

# The JVM's memory budget, decided here because it has to be decided before the
# JVM exists. Issue #587.
#
# The heap is not all a JVM holds. Beside it sit metaspace, the JIT's code
# cache, thread stacks, GC structures, malloc's own arenas and the GraalJS
# sandboxes' classes - 650 MB of this server's resident memory under heavy load,
# measured with native memory tracking beside /proc. These
# images used to give the heap 75% of the container's limit, which on a 2 GB pod
# left 512 MB for all of that: the heap was allowed to grow into memory the
# container did not have, and the kernel killed it at the limit (exit 137) the
# first time a busy hour let it. So the heap is the smaller of
#
#   ORKNUX_HEAP_PERCENT of the limit         (75)  - what large pods are given
#   the limit less ORKNUX_NATIVE_MEMORY_MB   (1024) - what small ones can afford
#
# A JAVA_OPTS that sizes the heap itself is left alone. Without a limit to read
# the JVM sizes against the machine, as before.
heap_options() {
    case " $JAVA_OPTS " in
        *" -Xmx"* | *"MaxHeapSize="* | *"MaxRAMPercentage="* | *"-XX:MaxRAM="*) return ;;
    esac
    percent="${ORKNUX_HEAP_PERCENT:-75}"
    native="${ORKNUX_NATIVE_MEMORY_MB:-1024}"
    case "$percent" in "" | *[!0-9]*) echo "orknux: ORKNUX_HEAP_PERCENT is not a number; using 75" >&2; percent=75 ;; esac
    case "$native" in "" | *[!0-9]*) echo "orknux: ORKNUX_NATIVE_MEMORY_MB is not a number; using 1024" >&2; native=1024 ;; esac
    if [ "$percent" -lt 10 ] || [ "$percent" -gt 95 ]; then
        echo "orknux: ORKNUX_HEAP_PERCENT is $percent, outside 10 to 95; using 75" >&2
        percent=75
    fi
    limit=""
    if [ -r /sys/fs/cgroup/memory.max ]; then
        limit="$(cat /sys/fs/cgroup/memory.max)"
    elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
        limit="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
    fi
    case "$limit" in
        "" | max | *[!0-9]*) echo "-XX:MaxRAMPercentage=$percent"; return ;;
    esac
    limit=$((limit / 1048576))
    # cgroup v1 says "unlimited" with a number nobody has.
    if [ "$limit" -gt 1048576 ]; then
        echo "-XX:MaxRAMPercentage=$percent"
        return
    fi
    heap=$((limit * percent / 100))
    if [ $((limit - native)) -lt "$heap" ]; then
        heap=$((limit - native))
    fi
    if [ "$heap" -lt 256 ]; then
        echo "orknux: WARNING a ${limit} MB memory limit leaves the heap ${heap} MB after ORKNUX_NATIVE_MEMORY_MB=${native}; using 256 MB, and the container may be killed at its limit. Give it at least 2 GB." >&2
        heap=256
    fi
    echo "orknux: memory limit ${limit} MB; heap ${heap} MB, ${native} MB kept for the JVM's own memory (ORKNUX_HEAP_PERCENT=${percent}, ORKNUX_NATIVE_MEMORY_MB=${native})" >&2
    echo "-Xmx${heap}m"
}
JAVA_OPTS="$(heap_options) ${JAVA_OPTS:-}"

# Two things outside the heap that grew without bound under load, both measured
# rather than guessed (#587):
#
# glibc gives every thread that mallocs its own arena, up to eight per core it
# can see - and it sees the host's cores, not the container's one. A 16-core
# node made 128 arenas, and 250 MB of memory they had freed and kept: resident,
# invisible to the JVM's own accounting, and enough to cross a 2 GB limit.
# ORKNUX_MALLOC_ARENAS caps them; MALLOC_ARENA_MAX itself, where set, wins.
#
# The JDK keeps a direct buffer per thread for I/O through a heap buffer, as big
# as the largest one that thread ever wrote, and keeps it for good: Tomcat's
# threads writing large responses held 170 MB of them. ORKNUX_NIO_BUFFER_CACHE_KB
# is the largest one kept; bigger ones are freed after use.
arenas="${ORKNUX_MALLOC_ARENAS:-2}"
case "$arenas" in "" | *[!0-9]*) arenas=2 ;; esac
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-$arenas}"
case " $JAVA_OPTS " in
    *"jdk.nio.maxCachedBufferSize"*) ;;
    *)
        cache="${ORKNUX_NIO_BUFFER_CACHE_KB:-256}"
        case "$cache" in "" | *[!0-9]*) cache=256 ;; esac
        JAVA_OPTS="-Djdk.nio.maxCachedBufferSize=$((cache * 1024)) $JAVA_OPTS"
        ;;
esac

# A JVM that has run out of heap is not a server that recovers. Every thread that
# next allocates fails on its own, so what follows is a process that answers
# health checks and drops whatever it was doing (#616) - exiting lets the
# container restart and, through the launcher, a release roll back. On by
# default; ORKNUX_EXIT_ON_OOM=false leaves the JVM to limp, for somebody taking
# a heap dump with -XX:+HeapDumpOnOutOfMemoryError who wants it to stay up.
case " $JAVA_OPTS " in
    *"OnOutOfMemoryError"*) ;;
    *)
        if [ "${ORKNUX_EXIT_ON_OOM:-true}" != "false" ]; then
            JAVA_OPTS="-XX:+ExitOnOutOfMemoryError $JAVA_OPTS"
        fi
        ;;
esac

if [ "${ORKNUX_SELF_UPDATE:-true}" = "false" ]; then
    if [ -n "${ORKNUX_RELEASE_PIN:-}" ]; then
        echo "orknux: ERROR ORKNUX_RELEASE_PIN is $ORKNUX_RELEASE_PIN and ORKNUX_SELF_UPDATE is false; the pin is ignored and the image's own jar runs" >&2
    fi
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -jar "$IMAGE_JAR"
fi

child=""
stopping=""
last_exit=""
last_jar=""

forward() {
    stopping="$1"
    if [ -n "$child" ]; then
        kill "-$1" "$child" 2>/dev/null
    fi
}
trap 'forward TERM' TERM
trap 'forward INT' INT

while :; do
    # The launcher never fails a start: on any trouble it prints the image's jar.
    # Its own failure to run at all (no JVM memory, say) is treated the same way.
    # shellcheck disable=SC2086
    jar="$(ORKNUX_LAST_EXIT="$last_exit" ORKNUX_LAST_JAR="$last_jar" java $JAVA_OPTS -cp "$IMAGE_JAR" \
        -Dloader.main=io.mszymanski.orknux.server.update.ReleaseLauncherKt \
        org.springframework.boot.loader.launch.PropertiesLauncher "$IMAGE_JAR" | tail -n 1)"
    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        jar="$IMAGE_JAR"
    fi

    # Asked to stop while the launcher was choosing: there is nothing to forward to.
    if [ -n "$stopping" ]; then
        exit 143
    fi

    # shellcheck disable=SC2086
    java $JAVA_OPTS \
        -Dorknux.update.launched=true \
        -Dorknux.update.running-jar="$jar" \
        -Dorknux.update.image-jar="$IMAGE_JAR" \
        -jar "$jar" &
    child=$!

    # `wait` returns early when a trapped signal arrives; wait again until the
    # JVM has actually gone, so its own status is the one reported.
    wait "$child"
    code=$?
    while kill -0 "$child" 2>/dev/null; do
        wait "$child"
        code=$?
    done
    child=""

    if [ -n "$stopping" ]; then
        exit "$code"
    fi
    if [ "$code" -eq 75 ]; then
        last_exit=""
        last_jar=""
        echo "orknux: the server asked to be started again; choosing a jar"
        continue
    fi
    # The image's own jar failing is the image's answer, and Docker's to restart.
    if [ "$jar" = "$IMAGE_JAR" ]; then
        exit "$code"
    fi
    # A stored release that ended on its own: the launcher is told, counts it,
    # and runs the image's own jar once it has had the starts it is allowed.
    # Exiting instead would leave it to a restart policy that may not exist,
    # and to a crash loop where one does. #593.
    last_exit="$code"
    last_jar="$jar"
    echo "orknux: release $jar exited with $code; choosing a jar"
done
