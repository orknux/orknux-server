# syntax=docker/dockerfile:1

# Build and package Orknux: the interface and the server, in one jar.
#
# Three stages, because Node, the JDK, the Maven repository and the source tree
# are all build-time concerns: shipping them would multiply the image size and
# hand anyone who pulls it the toolchain as well as the application.
#
# The interface used to be an image of its own, nginx in front of the bundle.
# Since #585 the server serves it from the jar, so a release - and an in-place
# update of one (#584) - is one artifact carrying both halves. The build context
# needs the orknux-ui submodule checked out.

# ---------------------------------------------------------------------------
# The interface bundle.
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim AS ui

WORKDIR /ui

# The lockfile first, on its own layer: dependencies change far less often than
# the code, so a source edit does not reinstall them.
COPY orknux-ui/package.json orknux-ui/package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY orknux-ui/ ./
RUN npm run build

# ---------------------------------------------------------------------------
# The server jar, carrying the bundle.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

# The poms first, on their own layer. Dependencies change far less often than
# code does, so resolving them again on every source edit is wasted minutes.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY app/pom.xml app/
COPY modules/connection/pom.xml modules/connection/
COPY modules/execution/pom.xml modules/execution/

RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY app/src app/src
COPY modules/connection/src modules/connection/src
COPY modules/execution/src modules/execution/src
# Where -Pwith-ui looks for it: the submodule's own build output.
COPY --from=ui /ui/dist orknux-ui/dist

# Tests are not run here. The suite brings up Postgres through Testcontainers,
# which needs a Docker daemon this build does not have — and a container build
# is the wrong place to find out a test fails. CI runs them as their own job,
# and this image is only built once they pass.
RUN ./mvnw -B -ntp package -DskipTests -Pwith-ui

FROM eclipse-temurin:25-jre AS runtime

# Not root. Nothing here needs to write outside its own working directory, and a
# container that cannot install anything is one less thing an exploit can use.
RUN groupadd --system orknux && useradd --system --gid orknux --create-home orknux

WORKDIR /app

COPY --from=build --chown=orknux:orknux /build/app/target/orknux-app-*.jar app.jar

# The start loop: the launcher chooses a jar - an update an administrator
# installed (#584), or this image's own - and the loop starts it again when it
# asks. CRLF stripped and the mode set for the same reason as orknux-one's
# entrypoint: this repository is worked on from Windows too.
COPY docker/orknux-run.sh /usr/local/bin/orknux-run
RUN sed -i 's/\r$//' /usr/local/bin/orknux-run && chmod +x /usr/local/bin/orknux-run

USER orknux

EXPOSE 8080

# The heap is sized by the start loop from the container's memory limit:
# ORKNUX_HEAP_PERCENT of it, but never more than the limit less
# ORKNUX_NATIVE_MEMORY_MB, which the JVM needs beside the heap (#587). A
# JAVA_OPTS that sizes the heap itself (-Xmx, MaxRAMPercentage) is left alone.
ENV JAVA_OPTS=""

# The image's own jar, which the launcher falls back to whenever a stored
# release is not right.
ENV ORKNUX_IMAGE_JAR=/app/app.jar

# The loop is PID 1 and hands SIGTERM and SIGINT on to the JVM, so `docker stop`
# still reaches it and the graceful shutdown in application.yml still means
# something. With ORKNUX_SELF_UPDATE=false it execs the JVM instead, as before.
ENTRYPOINT ["/usr/local/bin/orknux-run"]
