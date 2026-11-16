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
# The checks read the server's log for lines of ServerShutdown, QueuedPipeline, ImportService and App by their text:
# a reworded line fails its check here.

set -euo pipefail

IMAGE="${IMAGE:-altitude:local}"
START_TIMEOUT="${START_TIMEOUT:-1200}"
# compose.yaml's stop_grace_period
STOP_GRACE=120

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
photos_with_faces="$repo_root/altitude/test/resources/import/people"
other_photos="$repo_root/altitude/test/resources/import/images"

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

say() { printf '%s\n' "smoke-docker: $*"; }

# The server's log so far, stdout and stderr together
server_log() { docker logs "$name" 2>&1; }

fail() {
    printf '\n%s\n' "smoke-docker: FAILED: $1" >&2
    shift
    for detail in "$@"; do printf '%s\n' "$detail" | sed 's/^/    /' >&2; done
    exit 1
}

cleanup() {
    status=$?
    trap - EXIT INT TERM
    if [ "$passed" != 1 ] && [ "$container_made" = 1 ]; then
        {
            # The log's own lines, each of which starts with its date: a crash report's hundreds of lines are left out
            printf '\n%s\n' "smoke-docker: the last lines of the server's log, without DEBUG:"
            server_log | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}T' | grep -v ' DEBUG ' | tail -n 30 | cut -c1-300 || true
        } >&2
    fi
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

# request <file for the body> <curl arguments...>: prints the status code, 000 when there was no answer, which the
# caller's check then fails on by its name
request() {
    local body="$1"
    shift
    : >"$body"
    curl --silent --show-error --max-time 120 --output "$body" --write-out '%{http_code}' "$@" || true
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

# The session token of a response's Set-Cookie header, sent back as a Bearer token: the cookie is Secure, which curl
# would not send over plain HTTP
token_of() { sed -n 's/^[Ss]et-[Cc]ookie: auth_token=\([^;]*\);.*/\1/p' "$1" | tr -d '\r' | head -n 1; }

# Signs the Admin in again: sets $token. The session key is made anew at each start, so a restart signs everyone out.
sign_in() {
    local status
    status="$(request "$work/body" --dump-header "$work/headers" --data-urlencode 'login=smoke' \
        --data-urlencode "password=$password" "$url/login")"
    token="$(token_of "$work/headers")"
    if [ "$status" != 302 ] || [ -z "$token" ]; then
        fail "signing in after a restart" "status $status and no session cookie"
    fi
}

# Opens an Import of the files listed in a file (one path a line), uploads them and closes it: prints the Import's ID
import_files() {
    local list="$1" url="$2" token="$3" count status id file file_name
    count="$(grep -c . "$list")"
    status="$(request "$work/body" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        --data "{\"expectedFiles\":$count}" "$url/import/r/$repo/imports")"
    id="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' "$work/body")"
    if [ "$status" != 200 ] || [ -z "$id" ]; then
        fail "opening an Import" "status $status: $(head -c 300 "$work/body")"
    fi

    while IFS= read -r file; do
        file_name="$(basename "$file")"
        case "$file_name" in
            *[!A-Za-z0-9._-]*) fail "uploading $file_name" "the script sends a file's name in the URL as it is: letters, digits, dot, dash and underscore alone" ;;
        esac
        status="$(request "$work/body" -H "Authorization: Bearer $token" -H 'Content-Type: application/octet-stream' \
            --data-binary "@$file" "$url/import/r/$repo/imports/$id/files?relPath=$file_name")"
        [ "$status" = 200 ] || fail "uploading $file_name" "status $status: $(head -c 300 "$work/body")"
    done <"$list"

    status="$(request "$work/body" -H "Authorization: Bearer $token" -X POST "$url/import/r/$repo/imports/$id/close")"
    [ "$status" = 200 ] || fail "closing Import $id" "status $status: $(head -c 300 "$work/body")"
    printf '%s' "$id"
}

# The Import history, as the Import page shows it, into $work/history
read_history() {
    local status
    status="$(request "$work/history" -H "Authorization: Bearer $2" "$1/htmx/import/r/$repo/history")"
    [ "$status" = 200 ] || fail "reading the Import history" "status $status: $(head -c 300 "$work/history")"
}

# Whether the history last read shows the Import as in progress
in_progress() { grep -q "class=\"import in-progress\" data-import-id=\"$1\"" "$work/history"; }

# How many files of an Import the history last read counts under an outcome (imported, duplicate, unsupported, failed,
# pending): the number on that outcome's chip, 0 when there is none
outcome_count() {
    local count
    count="$(sed -n "/id=\"importStatus-$1\"/,/<\/ul>/p" "$work/history" |
        sed -n "s/.*<li class=\"$2\">\([0-9,]*\) .*/\1/p" | tr -d ',' | head -n 1)"
    printf '%s' "${count:-0}"
}

# How many lines of a file hold a fixed text
lines_with() { grep -c -F -e "$1" "$2" || true; }

require_line() { grep -q -F -e "$2" "$1" || fail "$3" "no line of the log says \"$2\""; }

forbid_line() {
    if grep -q -F -e "$2" "$1"; then
        fail "$3" "the log says: $(grep -F -e "$2" "$1" | head -n 3 | cut -c1-300)"
    fi
}

# ---------------------------------------------------------------------------------------------------------------------
# Before anything is made

for tool in docker curl ffmpeg; do
    command -v "$tool" >/dev/null 2>&1 || fail "the host's tools" "$tool is not installed"
done
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "the image" "$IMAGE does not exist: run make docker first"
[ -d "$photos_with_faces" ] && [ -d "$other_photos" ] || fail "the test photos" "not found under $repo_root/altitude/test/resources/import"

if [ -z "${MODELS_DIR:-}" ]; then
    # As ModelFiles.root: a relative XDG_CACHE_HOME is ignored
    case "${XDG_CACHE_HOME:-}" in
        /*) MODELS_DIR="$XDG_CACHE_HOME/altitude/models" ;;
        *) MODELS_DIR="$HOME/.cache/altitude/models" ;;
    esac
fi
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

setup='{"repositoryName":"Smoke","adminEmail":"smoke@example.com","adminName":"Smoke Test","adminUsername":"smoke"'
setup="$setup,\"password\":\"$password\",\"password2\":\"$password\"}"
status="$(request "$work/body" --dump-header "$work/headers" -H 'Content-Type: application/json' --data "$setup" \
    "$url/htmx/setup")"
repo="$(sed -n 's/^[Hh][Xx]-[Rr]edirect: \/r\/\([^[:space:]]*\).*/\1/p' "$work/headers" | tr -d '\r' | head -n 1)"
token="$(token_of "$work/headers")"
if [ "$status" != 200 ] || [ -z "$repo" ] || [ -z "$token" ]; then
    fail "Setup" "status $status, and no Library to go to or no session cookie: $(head -c 300 "$work/body")"
fi
say "   the Admin and Library $repo exist"

# ---------------------------------------------------------------------------------------------------------------------
say "4. an import of photos with faces and a video"

# A few seconds of a test pattern, in whatever codec this ffmpeg writes an .mp4 in: the image's FFmpeg opens, seeks and
# decodes it
ffmpeg -nostdin -loglevel error -y -f lavfi -i 'testsrc2=duration=3:size=640x360:rate=25' \
    -pix_fmt yuv420p "$work/clip.mp4" || fail "making the video" "ffmpeg -f lavfi failed"

find "$photos_with_faces" -type f | sort >"$work/first"
echo "$work/clip.mp4" >>"$work/first"
first_count="$(grep -c . "$work/first")"
first_import="$(import_files "$work/first" "$url" "$token")"

waited=0
while :; do
    read_history "$url" "$token"
    if ! in_progress "$first_import"; then break; fi
    [ "$waited" -lt 300 ] || fail "the first import" "still in progress after 300 seconds"
    sleep 1
    waited=$((waited + 1))
done
imported="$(outcome_count "$first_import" imported)"
[ "$imported" = "$first_count" ] || fail "the first import" \
    "$imported of $first_count files imported: $(sed -n "/id=\"importStatus-$first_import\"/,/<\/ul>/p" "$work/history" | grep '<li' | tr -s ' \n' ' ')"
say "   $imported files imported"

status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/search/r/$repo?triaged=true&rpp=100")"
[ "$status" = 200 ] || fail "listing the imported assets" "status $status"
[ "$(lines_with 'data-media-type="video"' "$work/body")" = 1 ] || fail "the video" "the results hold no video asset"

# Faces found: a person for each face not matched to another, and a cover face's image served from /data
status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/people/r/$repo/list?buckets=matched,unmatched&triaged=true")"
people="$(lines_with ' data-person-id="' "$work/body")"
if [ "$status" != 200 ] || [ "$people" -lt 1 ]; then
    fail "faces found" "status $status, and the People list holds $people people"
fi
face="$(sed -n "s/.*<img src=\"\(\/content\/r\/$repo\/[^\"]*\)\".*/\1/p" "$work/body" | head -n 1)"
[ -n "$face" ] || fail "faces found" "no person of the People list has a cover face"
status="$(request "$work/body" -H "Authorization: Bearer $token" "$url$face")"
if [ "$status" != 200 ] || [ ! -s "$work/body" ]; then
    fail "a face's image" "$face answered $status"
fi
say "   $people people found, and a face's image is served"

# Scene embeddings written: a Describe search ranks the images by their SigLIP 2 embeddings, which the import wrote
status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/search/r/$repo?describe=a%20person&triaged=true&rpp=100")"
described="$(sed -n 's/.*data-results-total="\([0-9]*\)".*/\1/p' "$work/body" | head -n 1)"
if [ "$status" != 200 ] || [ "${described:-0}" -lt 1 ]; then
    fail "Scene embeddings written" "a Describe search answered $status with ${described:-no} results"
fi
say "   a Describe search finds $described images"

# ---------------------------------------------------------------------------------------------------------------------
say "5. a stop with nothing under way, and a second start"

stop_server "a stop with nothing under way"
say "   stopped in ${stop_took}s with exit code 143"
start_server "the second start"
sign_in

# ---------------------------------------------------------------------------------------------------------------------
say "6. a stop in the middle of an import, right after a start"

# Right after a start, on purpose: the Scene models' sessions have not run yet. ONNX Runtime releases its environment in
# a JVM shutdown hook of its own, which runs at once, beside the drain; freed under a session's first pass, it would take
# the JVM down. The server holds a second reference to it (OnnxEnvironment), and this stop is what shows that it does.
find "$other_photos" -type f | sort >"$work/second"
second_count="$(grep -c . "$work/second")"
second_import="$(import_files "$work/second" "$url" "$token")"
# At once: the files are accepted and their Scene embeddings are under way or still to come
stop_server "the stop in the middle of an import"

stopping_at="$(grep -n -F 'Stopping: new requests are answered 503' "$work/log" | tail -n 1 | cut -d: -f1)"
[ -n "$stopping_at" ] || fail "the stop in the middle of an import" "no line of the log says the shutdown began"
tail -n "+$stopping_at" "$work/log" >"$work/log-stop"
head -n "$stopping_at" "$work/log" >"$work/log-before-stop"

settled="$(lines_with "of Import [$second_import] is " "$work/log")"
settled_before="$(lines_with "of Import [$second_import] is " "$work/log-before-stop")"
[ "$settled_before" -lt "$second_count" ] || fail "the stop's timing" \
    "all $second_count files were settled before the stop began, so it drained nothing"
[ "$settled" = "$second_count" ] || fail "the stop drained the import" \
    "$settled of $second_count files of the second import were settled when the server stopped"
require_line "$work/log-stop" 'Draining the import queue' "the stop drained the import"
require_line "$work/log-stop" 'The import queue has finished' "the stop drained the import"
forbid_line "$work/log-stop" 'did not finish within' "the stop drained the queues"
require_line "$work/log-stop" 'Cleaned up' "the stop closed the database"
require_line "$work/log-stop" 'The HTTP server has stopped' "the stop ended in order"
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
forbid_line "$work/log" 'a restart interrupted' "no Import left in progress"
# A JVM crash's report goes to stderr in the image, so to this log
forbid_line "$work/log" 'A fatal error has been detected by the Java Runtime Environment' "no crash report"
forbid_line "$work/log" 'SIGSEGV' "no crash report"
forbid_line "$work/log" 'SIGABRT' "no crash report"
forbid_line "$work/log" 'terminate called' "no crash report"
forbid_line "$work/log" 'java.lang.OutOfMemoryError' "no OutOfMemoryError"
# The image sets ORT_DISABLE_TELEMETRY: the start warns when it is not in force, and ONNX Runtime complains on a
# read-only filesystem when its telemetry is on
forbid_line "$work/log" "ONNX Runtime's telemetry is on" "ONNX Runtime's telemetry is off"
forbid_line "$work/log" 'Failed to persist telemetry device ID' "ONNX Runtime's telemetry is off"
forbid_line "$work/log" 'could not embed' "every image was embedded"
forbid_line "$work/log" ' ERROR ' "no error logged"
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
