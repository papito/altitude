#!/usr/bin/env bash
#
# Smoke-tests the Docker image (make smoke-docker, after make docker): starts it as the caller's user on a read-only
# root filesystem, with no capability and no new privileges, as compose.yaml does, runs Setup, imports photos with
# faces and a video, restarts it, stops it in the middle of a second import, starts it a third time, and checks what
# the image promises: a stop drains what was accepted, nothing is unpacked at runtime, the native libraries are the
# image's own architecture's, and the application's files are root's.
#
# On the host it needs docker (Engine 25 or later), curl, ffmpeg and bash with the usual tools (sed, grep, find, mktemp).
# The container is one of its own, with volumes of its own and a port Docker picks on 127.0.0.1; all of it is removed
# when the script ends, whether it passes or fails. It exits 0 when every check passes and 1 at the first that fails,
# naming it.
#
#   IMAGE          the image to test (altitude:local)
#   MODELS_DIR     the folder mounted as /models: the per-user models cache unless set ($XDG_CACHE_HOME/altitude/models,
#                  else ~/.cache/altitude/models). An empty one is filled by the start's download, about 1.6 GB.
#   START_TIMEOUT  the seconds a start may take, the model download included (1200)
#   KEEP           set to 1 to leave the container and its volumes in place after the run, for a look at a failure
#
# The checks read the server's log for lines of ServerShutdown, QueuedPipeline, ImportService, SceneEmbedder and App by
# their text: a reworded line fails its check here. That holds for a line that must not be there too: its check first
# finds the text in the sources, since a text no source logs any more is absent from every log. What it shares with
# smoke-jar.sh, the calls through the API and those checks among them, is in smoke-common.sh.

set -euo pipefail

IMAGE="${IMAGE:-altitude:local}"
START_TIMEOUT="${START_TIMEOUT:-1200}"
# compose.yaml's stop_grace_period
STOP_GRACE=120

SMOKE_NAME=smoke-docker
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/smoke-common.sh
. "$repo_root/scripts/smoke-common.sh"

name="altitude-smoke-$$-$(date +%s)"
data_volume="$name-data"
tmp_volume="$name-tmp"
work=""
container_made=0
passed=0
# The Admin's password in the scratch instance
password="smoke-test-password"
# Set by the functions below
url=""
token=""
stop_took=0

# The server's log so far, stdout and stderr together
server_log() { docker logs "$name" 2>&1; }

cleanup() {
    status=$?
    trap - EXIT INT TERM
    if [ "$passed" != 1 ] && [ "$container_made" = 1 ]; then server_log | print_log_tail || true; fi
    if [ "${KEEP:-0}" = 1 ]; then
        say "KEEP=1: left container $name and volumes $data_volume and $tmp_volume"
    else
        docker rm -f -v "$name" >/dev/null 2>&1 || true
        docker volume rm "$data_volume" "$tmp_volume" >/dev/null 2>&1 || true
    fi
    if [ -n "$work" ]; then rm -rf "$work"; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------------------------------------------------
# What the checks are built from

# The base URL of the published port, which Docker picks anew at each start
base_url() {
    local published
    published="$(docker port "$name" 8080/tcp | head -n 1)"
    [ -n "$published" ] || fail "the published port" "docker port names none for 8080/tcp"
    printf 'http://%s' "$published"
}

# Waits until Docker's health check passes. A container that exits, or that Docker calls unhealthy, fails at once.
wait_until_healthy() {
    local what="$1" waited=0 state
    while :; do
        state="$(docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' "$name")"
        case "$state" in
            "running healthy") return 0 ;;
            "running starting") ;;
            *) fail "$what" "the container is \"$state\", exit code $(docker inspect --format '{{.State.ExitCode}}' "$name")" ;;
        esac
        if [ "$waited" -ge "$START_TIMEOUT" ]; then
            fail "$what" "not healthy after $START_TIMEOUT seconds (START_TIMEOUT)"
        fi
        # A first start downloads the model files: show that it is moving
        if [ "$waited" -gt 0 ] && [ $((waited % 30)) = 0 ]; then
            say "  still starting after ${waited}s: $(server_log | grep -v ' DEBUG ' | tail -n 1 | cut -c1-160)"
        fi
        sleep 2
        waited=$((waited + 2))
    done
}

# Stops the server as `docker compose stop` does, with compose.yaml's grace period, and saves its log as $work/log. The
# JVM must end by the SIGTERM it handled, exit code 143: 137 is Docker's SIGKILL after the grace period, and any other
# is a crash, whose report the image sends to the log.
stop_server() {
    local what="$1" started exit_code crash
    started="$(date +%s)"
    docker stop -t "$STOP_GRACE" "$name" >/dev/null
    stop_took=$(($(date +%s) - started))
    server_log >"$work/log"
    exit_code="$(docker inspect --format '{{.State.ExitCode}}' "$name")"
    if [ "$exit_code" != 143 ]; then
        crash="$(grep -A 12 -F 'A fatal error has been detected' "$work/log" | grep -E 'SIG[A-Z]+ |^# [CVJjv] ' |
            awk '!seen[$0]++' | head -n 4 | cut -c1-200 || true)"
        fail "$what" "the container's exit code is $exit_code, not 143, after $stop_took seconds" ${crash:+"the JVM crashed:" "$crash"}
    fi
}

# Starts the stopped server again and waits for it: sets $url, since Docker picks the published port anew
start_server() {
    docker start "$name" >/dev/null
    wait_until_healthy "$1"
    url="$(base_url)"
}

# ---------------------------------------------------------------------------------------------------------------------
# Before anything is made

require_tools docker curl ffmpeg
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "the image" "$IMAGE does not exist: run make docker first"

MODELS_DIR="${MODELS_DIR:-$(user_cache_dir)/models}"
# Made here, by the caller: a bind mount's missing folder would be made by Docker, as root
mkdir -p "$MODELS_DIR"
MODELS_DIR="$(cd "$MODELS_DIR" && pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/altitude-smoke.XXXXXX")"
user="$(id -u):$(id -g)"
architecture="$(docker image inspect --format '{{.Architecture}}' "$IMAGE")"
say "image $IMAGE ($architecture), as user $user, models in $MODELS_DIR"

# ---------------------------------------------------------------------------------------------------------------------
say "1. the image holds native libraries of its own architecture alone"

# The e_machine of an ELF file, the two bytes at offset 18: 3e 00 is x86-64 and b7 00 is AArch64
case "$architecture" in
    amd64) machine=3e00 ;;
    arm64) machine=b700 ;;
    *) fail "the image's architecture" "$architecture is neither amd64 nor arm64" ;;
esac
# shellcheck disable=SC2016  # the script is the container's shell's to expand
docker run --rm --entrypoint sh "$IMAGE" -c '
    find /app/native -type f | while read -r file; do
        printf "%s%s %s\n" "$(od -An -tx1 -N4 "$file" | tr -d " \n")" "$(od -An -tx1 -j18 -N2 "$file" | tr -d " \n")" "$file"
    done' >"$work/natives"
natives="$(grep -c . "$work/natives" || true)"
[ "$natives" -gt 0 ] || fail "the image's native libraries" "/app/native holds no file"
if grep -v "^7f454c46$machine " "$work/natives" >"$work/foreign"; then
    fail "the image's native libraries" "not ELF files for $architecture (magic and machine, then the file):" "$(head -n 10 "$work/foreign")"
fi
say "   $natives files in /app/native, all ELF for $architecture"

# The application's own files are root's, so the user the server runs as cannot replace or add one, even here, where
# the root filesystem is writable. A file it could write would be run at the next start.
# shellcheck disable=SC2016  # the script is the container's shell's to expand
writable="$(docker run --rm --user "$user" --entrypoint sh "$IMAGE" -c '
    find /app ! -user 0
    find /app \( -type d -o -type f \) -writable
    for dir in /app /app/lib /app/native; do touch "$dir/written" 2>/dev/null && echo "$dir/written was made"; done
    true' | head -n 10)"
[ -z "$writable" ] || fail "the application's files are root's" "not root's, or writable by $user:" "$writable"
say "   /app is root's, and $user can write nothing in it"

# ---------------------------------------------------------------------------------------------------------------------
say "2. the first start, on a read-only root filesystem"

docker volume create "$data_volume" >/dev/null
docker volume create "$tmp_volume" >/dev/null
container_made=1
if [ "$user" != "1000:1000" ]; then
    # A new volume takes /data's owner in the image, UID 1000: another caller is given it, as compose's `user:` needs of
    # a folder
    docker run --rm --user 0:0 --entrypoint chown -v "$data_volume:/data" "$IMAGE" -R "$user" /data
fi

# As compose.yaml runs it: a read-only root filesystem, no capability and no new privileges.
# DEBUG, for the line each Import file's outcome is logged in
docker run --detach --name "$name" --user "$user" --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --volume "$data_volume:/data" --volume "$tmp_volume:/tmp" --volume "$MODELS_DIR:/models" \
    --publish 127.0.0.1::8080 --env ALTITUDE_LOG_LEVEL=DEBUG --stop-timeout "$STOP_GRACE" "$IMAGE" >/dev/null

wait_until_healthy "the first start"
url="$(base_url)"
status="$(request "$work/body" "$url/api/health")"
if [ "$status" != 200 ] || ! grep -q '"status":"ok"' "$work/body"; then
    fail "the health check from the host" "$url/api/health answered $status: $(head -c 300 "$work/body")"
fi
say "   healthy, and $url/api/health answers ok"

# ---------------------------------------------------------------------------------------------------------------------
say "3. Setup"

run_setup
say "   the Admin and Library $repo exist"

# ---------------------------------------------------------------------------------------------------------------------
say "4. an import of photos with faces and a video"

import_faces_and_video

# ---------------------------------------------------------------------------------------------------------------------
say "5. a stop with nothing under way, and a second start"

# What a run that is killed leaves in the server's temp folder, which the next start must empty: /tmp is a volume that
# outlives the run. The JVM's own files are in /tmp itself, where a start removes nothing.
docker exec "$name" sh -c 'echo spooled >/tmp/altitude/undertow-leftover && mkdir /tmp/altitude/apache-tika-leftover &&
    echo kept >/tmp/beside-the-temp-folder' || fail "the temp folder" "/tmp/altitude is not there to write to"
stop_server "a stop with nothing under way"
say "   stopped in ${stop_took}s with exit code 143"
start_server "the second start"
sign_in
# shellcheck disable=SC2016  # the script is the container's shell's to expand
temp_state="$(docker exec "$name" sh -c '
    ls -A /tmp/altitude | grep leftover
    [ -f /tmp/beside-the-temp-folder ] || echo "/tmp/beside-the-temp-folder is gone"
    ls /tmp/hsperfdata_*/1 >/dev/null 2>&1 || [ ! -d /tmp/hsperfdata_* ] || echo "the running JVM lost its hsperfdata file"
    true' 2>&1)"
[ -z "$temp_state" ] || fail "the start empties its temp folder, and that alone" "$temp_state"
say "   the second start emptied /tmp/altitude and left the rest of /tmp alone"

# ---------------------------------------------------------------------------------------------------------------------
say "6. a stop in the middle of an import, right after a start"

# Right after a start, on purpose: the Scene models' sessions have not run yet. ONNX Runtime releases its environment in
# a JVM shutdown hook of its own, which runs at once, beside the drain; freed under a session's first pass, it would take
# the JVM down. The server holds a second reference to it (OnnxEnvironment), and this stop is what shows that it does.
#
# The stop must find files still in the pipeline, which is a race against the machine: the photos are few and a fast one
# imports them while the last is still being uploaded. So the import holds three more photos a core than the test's
# own (by the core, as the import's threads are, so the drain takes about as long on any machine and stays within the
# Pipeline shutdown timeout), and when it has all settled before the stop began all the same, the step is run once more
# with other photos before it fails.
cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
extra_photos=$((cores * 3))
attempt=1
while :; do
    if [ "$attempt" = 1 ]; then
        find "$other_photos" -type f | sort >"$work/second"
        make_photos "$extra_photos" "$work/frames-1" "$work/second" testsrc2
    else
        : >"$work/second"
        make_photos "$((extra_photos * 2))" "$work/frames-2" "$work/second" testsrc
    fi
    second_count="$(grep -c . "$work/second" || true)"
    second_import="$(import_files "$work/second" "$url" "$token")"
    # At once: the files are accepted and their Scene embeddings are under way or still to come
    stop_server "the stop in the middle of an import"

    require_drain_lines "$work/log" "the stop drained the import"

    settled="$(lines_with "of Import [$second_import] is " "$work/log")"
    settled_before="$(lines_with "of Import [$second_import] is " "$work/log-before-stop")"
    [ "$settled_before" -ge "$second_count" ] || break
    [ "$attempt" = 1 ] || fail "the stop's timing" \
        "twice, all the files of an import ($second_count the second time) were settled before the stop began, so it drained nothing"
    say "   all $second_count files were settled before the stop began: once more, with other photos"
    attempt=2
    start_server "the start before the second try"
    sign_in
done
[ "$settled" = "$second_count" ] || fail "the stop drained the import" \
    "$settled of $second_count files of the second import were settled when the server stopped"
say "   stopped in ${stop_took}s with exit code 143: the drain settled $((second_count - settled_before)) of $second_count files"

# ---------------------------------------------------------------------------------------------------------------------
say "7. a third start finds no Import left in progress"

start_server "the third start"
sign_in
read_history "$url" "$token"
if in_progress "$first_import" || in_progress "$second_import"; then
    fail "no Import left in progress" "the Import history shows one in progress"
fi
failed="$(outcome_count "$second_import" failed)"
pending="$(outcome_count "$second_import" pending)"
done_count=$(($(outcome_count "$second_import" imported) + $(outcome_count "$second_import" duplicate)))
done_count=$((done_count + $(outcome_count "$second_import" unsupported)))
if [ "$failed" != 0 ] || [ "$pending" != 0 ] || [ "$done_count" != "$second_count" ]; then
    fail "the second import's files" "of $second_count: $done_count done, $failed failed, $pending pending"
fi
say "   the second import shows $done_count of $second_count files done, none failed"

# ---------------------------------------------------------------------------------------------------------------------
say "8. the log of the three runs"

server_log >"$work/log"
# A start closes each Import still in progress and fails its pending files, with this WARN
forbid_own_line "$work/log" 'a restart interrupted' "no Import left in progress"
# A JVM crash's report goes to stderr in the image, so to this log. The image sets ORT_DISABLE_TELEMETRY.
forbid_failure_lines "$work/log"
say "   no interrupted Import, no crash report, no error, no telemetry"

# ---------------------------------------------------------------------------------------------------------------------
say "9. nothing was unpacked or written outside the volumes"

read_only="$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$name")"
[ "$read_only" = true ] || fail "the read-only root filesystem" "the container is not read-only"
dropped="$(docker inspect --format '{{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' "$name")"
[ "$dropped" = "[ALL] [no-new-privileges:true]" ] || fail "the container's privileges" "CapDrop and SecurityOpt are $dropped"
changed="$(docker diff "$name")"
[ -z "$changed" ] || fail "the read-only root filesystem" "the container's filesystem changed:" "$changed"
if docker exec "$name" sh -c 'touch /app/written 2>/dev/null'; then
    fail "the read-only root filesystem" "a file could be written in /app"
fi
# javacpp's cache would be a .javacpp folder in the home folder or, with no home, in the temp folder; a library
# unpacked by it, by ONNX Runtime or by sqlite-jdbc would be a .so file in the temp folder
# shellcheck disable=SC2016  # the script is the container's shell's to expand
unpacked="$(docker exec "$name" sh -c '
    ls -d "${HOME:-}/.javacpp" 2>/dev/null
    find /tmp /data -xdev \( -name "*javacpp*" -o -name "*.so" -o -name "*.so.*" \) 2>/dev/null | head -n 20')"
[ -z "$unpacked" ] || fail "nothing unpacked at runtime" "found:" "$unpacked"
say "   the root filesystem is unchanged, and no library or javacpp cache is in /tmp, /data or the home folder"

passed=1
say "PASSED"
