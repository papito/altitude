# The Altitude image: thin jars and one architecture's native libraries in /app, root's and read-only to the user
# that runs them, run by a JRE as UID 1000 with the Data directory at /data and the Models directory at /models.
# `make docker` builds it; compose.yaml runs it.
#
# The two build stages run on the build machine's own architecture, whatever the image's: Mill only resolves, compiles
# and repacks, so nothing of the build runs under emulation. They share Mill's out/ as a cache mount, which one stage
# holds at a time (sharing=locked), and coursier's and the Mill launcher's downloads as two more. A cache mount is no
# part of a stage's filesystem, so each RUN copies its result out to /dist, where COPY --from finds it.
#
# No `# syntax=` line: the cache mounts and the health check's start interval are in the Dockerfile frontend built
# into every Docker Engine the image runs on (25 and later), and a named frontend is one more pull at every build.

# ---------------------------------------------------------------------------------------------------------------------
# lib: /dist/lib (the dependency jars, their natives stripped) and /dist/altitude.jar, the same bytes for every
# architecture. The stage names no target architecture, so an image built for two compiles once.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS lib
WORKDIR /src
# The build's sources and nothing else of the context (.dockerignore drops the tests and the guides under altitude/)
COPY mill build.mill ./
COPY altitude altitude
# --no-daemon: a daemon would outlive the task and leave its files in the cache mount for the next build to trip on
RUN --mount=type=cache,id=altitude-coursier,target=/root/.cache/coursier \
    --mount=type=cache,id=altitude-mill-launcher,target=/root/.cache/mill \
    --mount=type=cache,id=altitude-mill-out,target=/src/out,sharing=locked \
    ./mill --no-daemon altitude.distLib \
    && mkdir /dist \
    && cp -a out/altitude/distLib.dest/. /dist/

# ---------------------------------------------------------------------------------------------------------------------
# native: /dist/native (the target architecture's libraries, flat) and /dist/jvm-options (the flags that make each of
# them load from /app/native). It copies only what the task reads, so a change to the application's sources leaves it
# cached.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS native
WORKDIR /src
COPY mill build.mill ./
COPY altitude/resources/sqlite-vector altitude/resources/sqlite-vector
# Docker's name of the image's architecture, which the build knows as ALTITUDE_PLATFORM
ARG TARGETARCH
# cp -a keeps native/'s links: copied as files, the image grows by about 165 MB
RUN --mount=type=cache,id=altitude-coursier,target=/root/.cache/coursier \
    --mount=type=cache,id=altitude-mill-launcher,target=/root/.cache/mill \
    --mount=type=cache,id=altitude-mill-out,target=/src/out,sharing=locked \
    case "$TARGETARCH" in \
        amd64) platform=linux-x86_64 ;; \
        arm64) platform=linux-arm64 ;; \
        *) echo "Altitude has no image for $TARGETARCH: linux/amd64 and linux/arm64 alone" >&2; exit 1 ;; \
    esac \
    && ALTITUDE_PLATFORM="$platform" ./mill --no-daemon altitude.distNative \
    && mkdir /dist \
    && cp -a out/altitude/distNative.dest/. /dist/

# ---------------------------------------------------------------------------------------------------------------------
# volumes: the folders the image's volumes are mounted on, empty. Made here so the runtime stage needs no second RUN to
# create and chown them; its COPY --chown gives them to UID 1000.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS volumes
RUN mkdir -p /volumes/data/db /volumes/models

# ---------------------------------------------------------------------------------------------------------------------
# The image. Ubuntu 24.04 (glibc 2.39), with curl for the health check.
FROM eclipse-temurin:21-jre-noble

# The stage's one RUN, and first, so no change to the application rebuilds its layer; in a cross-build it is the only
# step run under emulation. bytedeco's opencv_highgui links GTK 2 and its avdevice links ALSA and XCB: without the four
# packages OpenCV does not load at the start and no video opens, though Altitude shows no window and plays no sound.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgtk2.0-0t64 libasound2t64 libxcb1 libxcb-shm0 \
    && rm -rf /var/lib/apt/lists/*

# /data, /data/db and /models, empty and owned by UID 1000, the base image's `ubuntu` user: a named volume first mounted
# on one of them takes its owner, so it is writable. /data/db is there for a volume of its own, which keeps the SQLite
# database on a local disk while /data is a host folder.
COPY --from=volumes --chown=1000:1000 /volumes/ /

# The dependency jars are a layer of their own, as are the native libraries: a build that changes the application alone
# replaces altitude.jar's layer, the last and the smallest.
#
# No --chown: /app and all it holds are root's, which UID 1000 (or compose's `user:`) can read and cannot write. Code
# run through a media decoder then cannot replace a jar or a library, nor add one to lib/*, for the next start to run,
# whether or not the root filesystem is read-only (it is under compose.yaml, not under a plain `docker run`).
COPY --from=lib /dist/lib /app/lib
COPY --from=native /dist/native /app/native
COPY --from=native /dist/jvm-options /app/jvm-options
COPY --from=lib /dist/altitude.jar /app/altitude.jar

USER 1000:1000

# ALTITUDE_HOST: every interface of the container, where the default, localhost, would take no request from outside it.
# LD_LIBRARY_PATH: bytedeco's libraries name each other with no path of their own.
# ORT_DISABLE_TELEMETRY: ONNX Runtime sends usage data to Microsoft unless this is set before its library loads.
ENV ALTITUDE_HOST=0.0.0.0 \
    ALTITUDE_DATA_DIR=/data \
    ALTITUDE_MODELS_DIR=/models \
    LD_LIBRARY_PATH=/app/native \
    ORT_DISABLE_TELEMETRY=1

# /data is what a backup covers; /models can be downloaded again
VOLUME /data /models

EXPOSE 8080

# The first start answers no request until the model files are downloaded (about 1.6 GB), so for 15 minutes a failed
# check leaves the container `starting`, not `unhealthy`, and the check runs every 5 seconds to find the first answer
# soon. The start interval needs Docker Engine 25. Shell form, so the port is read when the check runs.
HEALTHCHECK --start-period=15m --start-interval=5s \
    CMD curl -fsS -o /dev/null "http://localhost:${ALTITUDE_PORT:-8080}/api/health" || exit 1

# Exec form: the JVM is PID 1 and takes the stop's SIGTERM itself, which starts the graceful shutdown.
# -Daltitude.env=prod wins over ALTITUDE_ENV, so the image runs as prod whichever environment the variable names; one
# that names none still stops the start.
# -XX:+ExitOnOutOfMemoryError ends the process on an OutOfMemoryError for the restart policy to start it again;
# -XX:+ErrorFileToStderr sends a JVM crash's report to `docker logs`, the root filesystem being read-only.
ENTRYPOINT ["java", "@/app/jvm-options", "-Daltitude.env=prod", \
    "-XX:+ExitOnOutOfMemoryError", "-XX:+ErrorFileToStderr", \
    "-cp", "/app/altitude.jar:/app/lib/*", "altitude.core.App"]
