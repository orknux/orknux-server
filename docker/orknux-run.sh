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
# again", so it loops. Anything else is the server's own answer and this exits
# with it, which is what Docker and Kubernetes restart on.
#
# This shell is PID 1 and the JVM is its child, so a signal has to be handed on
# by hand: SIGTERM from `docker stop` or a pod deletion goes to the JVM, which
# shuts down gracefully, and its exit status is this container's. A shell that
# did not trap would leave the JVM to be killed at the end of the grace period.
#
# ORKNUX_SELF_UPDATE=false skips the launcher and execs the image's jar, exactly
# as these images did before the feature existed.

IMAGE_JAR="${ORKNUX_IMAGE_JAR:-/app/app.jar}"

if [ "${ORKNUX_SELF_UPDATE:-true}" = "false" ]; then
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -jar "$IMAGE_JAR"
fi

child=""
stopping=""

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
    jar="$(java $JAVA_OPTS -cp "$IMAGE_JAR" \
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

    if [ "$code" -ne 75 ] || [ -n "$stopping" ]; then
        exit "$code"
    fi
    echo "orknux: the server asked to be started again; choosing a jar"
done
